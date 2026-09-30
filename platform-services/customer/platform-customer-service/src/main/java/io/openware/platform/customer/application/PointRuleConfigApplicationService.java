package io.openware.platform.customer.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.platform.customer.infra.persistence.mapper.PointRuleConfigMapper;
import io.openware.platform.customer.infra.persistence.po.CstPointRuleConfigPo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.List;

/** 积分规则的唯一写入/解析入口；Customer 域只持有自己的规则值。 */
@Service
public class PointRuleConfigApplicationService {
    public static final String ACTIVE = "ACTIVE";
    private static final BigDecimal DEFAULT_RATE = BigDecimal.ONE;
    private final PointRuleConfigMapper mapper;
    private final AuditClient auditClient;

    public PointRuleConfigApplicationService(PointRuleConfigMapper mapper) {
        this(mapper, AuditClient.disabled());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PointRuleConfigApplicationService(PointRuleConfigMapper mapper, AuditClient auditClient) {
        this.mapper = mapper;
        this.auditClient = auditClient;
    }

    /** 作用域优先级：门店覆盖 > 业态默认 > 租户默认。 */
    public PointRule resolve(Long tenantId, Long storeId, String businessType) {
        if (tenantId == null || tenantId <= 0) return PointRule.DEFAULT;
        String bt = normalize(businessType);
        LambdaQueryWrapper<CstPointRuleConfigPo> q = new LambdaQueryWrapper<CstPointRuleConfigPo>()
                .eq(CstPointRuleConfigPo::getTenantId, tenantId)
                .eq(CstPointRuleConfigPo::getStatus, ACTIVE);
        List<CstPointRuleConfigPo> rows = mapper.selectList(q);
        CstPointRuleConfigPo selected = rows.stream()
                .filter(r -> storeId != null && storeId > 0 && storeId.equals(r.getStoreId()) && same(bt, r.getBusinessType()))
                .findFirst().orElseGet(() -> rows.stream()
                .filter(r -> r.getStoreId() != null && r.getStoreId().equals(0L) && same(bt, r.getBusinessType()))
                        .findFirst().orElseGet(() -> rows.stream()
                                .filter(r -> r.getStoreId() != null && r.getStoreId().equals(0L) && blank(r.getBusinessType()))
                                .findFirst().orElse(null)));
        return selected == null ? PointRule.DEFAULT : PointRule.from(selected);
    }

    @Transactional
    public CstPointRuleConfigPo save(SaveCommand command) {
        try {
            validate(command);
            long tenantId = command.tenantId();
            String bt = normalize(command.businessType());
            long storeId = command.storeId() == null ? 0L : command.storeId();
            if (command.idempotencyKey() != null && !command.idempotencyKey().isBlank()) {
                CstPointRuleConfigPo byKey = mapper.selectOne(new LambdaQueryWrapper<CstPointRuleConfigPo>()
                        .eq(CstPointRuleConfigPo::getTenantId, tenantId)
                        .eq(CstPointRuleConfigPo::getIdempotencyKey, command.idempotencyKey())
                        .last("LIMIT 1"));
                if (byKey != null) return byKey;
            }
            CstPointRuleConfigPo existing = mapper.selectOne(new LambdaQueryWrapper<CstPointRuleConfigPo>()
                    .eq(CstPointRuleConfigPo::getTenantId, tenantId)
                    .eq(CstPointRuleConfigPo::getBusinessType, bt == null ? "" : bt)
                    .eq(CstPointRuleConfigPo::getStoreId, storeId)
                    .last("LIMIT 1"));
            if (existing != null && command.version() != null && !command.version().equals(existing.getVersion())) {
                throw new ApiException(409, "VERSION_CONFLICT", "积分规则已被其他请求修改");
            }
            CstPointRuleConfigPo po = existing == null ? new CstPointRuleConfigPo() : existing;
            po.setTenantId(tenantId);
            po.setBusinessType(bt == null ? "" : bt);
            po.setStoreId(storeId);
            po.setEarnRate(command.earnRate() == null ? DEFAULT_RATE : command.earnRate());
            po.setRedeemRate(command.redeemRate() == null ? DEFAULT_RATE : command.redeemRate());
            po.setExpiryDays(command.expiryDays() == null ? 0 : command.expiryDays());
            po.setRedeemCapPoints(command.redeemCapPoints() == null ? 0L : command.redeemCapPoints());
            po.setStatus(ACTIVE);
            po.setIdempotencyKey(command.idempotencyKey());
            po.setUpdatedAt(LocalDateTime.now());
            if (existing == null) {
                po.setVersion(0);
                po.setCreatedAt(LocalDateTime.now());
                mapper.insert(po);
            } else {
                po.setVersion(existing.getVersion() == null ? 1 : existing.getVersion() + 1);
                mapper.updateById(po);
            }
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .action("points.rule.save").resourceType("cst_point_rule_config")
                    .resourceId(String.valueOf(po.getId())).idempotencyKey(command.idempotencyKey())
                    .detailJson("{\"tenantId\":" + tenantId + ",\"businessType\":\"" + po.getBusinessType()
                            + "\",\"storeId\":" + storeId + "}").build());
            return po;
        } catch (RuntimeException failure) {
            auditClient.recordAsync(AuditClient.AuditRecord.builder().action("points.rule.save")
                    .resourceType("cst_point_rule_config").result(AuditClient.AuditRecord.RESULT_FAILURE)
                    .errorCode(AuditErrorCodes.of(failure)).build());
            throw failure;
        }
    }

    private static void validate(SaveCommand c) {
        if (c == null || c.tenantId() <= 0) throw new ApiException(400, "TENANT_ID_REQUIRED", "缺少租户上下文");
        long storeId = c.storeId() == null ? 0L : c.storeId();
        String bt = normalize(c.businessType());
        if (storeId > 0 && bt == null) throw new ApiException(400, "BUSINESS_TYPE_REQUIRED", "门店规则必须指定业态");
        if (storeId == 0 && bt == null && c.redeemCapPoints() != null && c.redeemCapPoints() > 0) {
            throw new ApiException(422, "RULE_SCOPE_INVALID", "积分抵扣上限只允许业态或门店级配置");
        }
        if (storeId > 0 && c.expiryDays() != null && c.expiryDays() > 0) {
            throw new ApiException(422, "RULE_SCOPE_INVALID", "积分有效期不允许单店覆盖");
        }
        if (c.earnRate() != null && c.earnRate().signum() <= 0 || c.redeemRate() != null && c.redeemRate().signum() <= 0) {
            throw new ApiException(400, "RULE_VALUE_INVALID", "积分倍率必须大于0");
        }
        if (c.expiryDays() != null && c.expiryDays() < 0 || c.redeemCapPoints() != null && c.redeemCapPoints() < 0) {
            throw new ApiException(400, "RULE_VALUE_INVALID", "积分规则数值不能为负数");
        }
    }

    private static String normalize(String value) { return blank(value) ? null : value.trim().toUpperCase(Locale.ROOT); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean same(String a, String b) { return (a == null ? "" : a).equals(normalize(b) == null ? "" : normalize(b)); }

    public record SaveCommand(long tenantId, Long storeId, String businessType, BigDecimal earnRate,
                              BigDecimal redeemRate, Integer expiryDays, Long redeemCapPoints,
                              Integer version, String idempotencyKey) {}

    public record PointRule(BigDecimal earnRate, BigDecimal redeemRate, int expiryDays, long redeemCapPoints,
                            int version, long id, String businessType, long storeId) {
        static final PointRule DEFAULT = new PointRule(DEFAULT_RATE, DEFAULT_RATE, 0, 0, 0, 0, "", 0);
        static PointRule from(CstPointRuleConfigPo p) {
            return new PointRule(p.getEarnRate(), p.getRedeemRate(), p.getExpiryDays() == null ? 0 : p.getExpiryDays(),
                    p.getRedeemCapPoints() == null ? 0 : p.getRedeemCapPoints(), p.getVersion() == null ? 0 : p.getVersion(),
                    p.getId(), p.getBusinessType(), p.getStoreId() == null ? 0 : p.getStoreId());
        }
    }
}
