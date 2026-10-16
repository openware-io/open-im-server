package io.openware.platform.customer.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.platform.customer.domain.point.model.PointRuleConfig;
import io.openware.platform.customer.domain.point.repository.PointRuleConfigRepository;
import io.openware.platform.customer.domain.point.service.PointRuleResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;

/** 积分规则的唯一写入/解析入口；Customer 域只持有自己的规则值。 */
@Service
public class PointRuleConfigApplicationService {
    public static final String ACTIVE = PointRuleConfig.STATUS_ACTIVE;
    private static final BigDecimal DEFAULT_RATE = PointRuleConfig.DEFAULT_RATE;
    private final PointRuleConfigRepository repository;
    private final AuditClient auditClient;

    public PointRuleConfigApplicationService(PointRuleConfigRepository repository) {
        this(repository, AuditClient.disabled());
    }

    @Autowired
    public PointRuleConfigApplicationService(PointRuleConfigRepository repository, AuditClient auditClient) {
        this.repository = repository;
        this.auditClient = auditClient;
    }

    /** 作用域优先级：门店覆盖 > 业态默认 > 租户默认（口径在 {@link PointRuleResolver}）。 */
    public PointRule resolve(Long tenantId, Long storeId, String businessType) {
        if (tenantId == null || tenantId <= 0) return PointRule.DEFAULT;
        String bt = normalize(businessType);
        return PointRuleResolver
            .resolve(repository.listActiveByTenant(tenantId), storeId, bt)
            .map(PointRule::from)
            .orElse(PointRule.DEFAULT);
    }

    @Transactional
    public PointRuleConfig save(SaveCommand command) {
        try {
            validate(command);
            long tenantId = command.tenantId();
            String bt = normalize(command.businessType());
            long storeId = command.storeId() == null ? PointRuleConfig.TENANT_LEVEL_STORE_ID : command.storeId();

            // 幂等优先：同一幂等键已落库则直接回放，不再改库。
            if (command.idempotencyKey() != null && !command.idempotencyKey().isBlank()) {
                Optional<PointRuleConfig> byKey = repository.findByIdempotencyKey(tenantId, command.idempotencyKey());
                if (byKey.isPresent()) return byKey.get();
            }

            String scopedBusinessType = bt == null ? PointRuleConfig.ANY_BUSINESS_TYPE : bt;
            Optional<PointRuleConfig> existing = repository.findByScope(tenantId, scopedBusinessType, storeId);

            PointRuleConfig saved;
            if (existing.isEmpty()) {
                // 新增：版本从 0 起。并发同作用域由生产唯一键兜底（撞键则整体回滚，不会产生两行配置）。
                saved = repository.insert(new PointRuleConfig(null, tenantId, scopedBusinessType, storeId,
                    command.earnRate() == null ? DEFAULT_RATE : command.earnRate(),
                    command.redeemRate() == null ? DEFAULT_RATE : command.redeemRate(),
                    command.expiryDays() == null ? 0 : command.expiryDays(),
                    command.redeemCapPoints() == null ? 0L : command.redeemCapPoints(),
                    0, command.idempotencyKey(), ACTIVE));
            } else {
                PointRuleConfig current = existing.get();
                int expectedVersion = current.currentVersion();
                // 调用方显式带了版本号时，先按它做一次"快失败"判断，给出 409 而不是静默覆盖。
                if (command.version() != null && !command.version().equals(current.version())) {
                    throw new ApiException(409, "VERSION_CONFLICT", "积分规则已被其他请求修改");
                }
                PointRuleConfig updated = new PointRuleConfig(current.id(), tenantId, scopedBusinessType,
                    storeId,
                    command.earnRate() == null ? DEFAULT_RATE : command.earnRate(),
                    command.redeemRate() == null ? DEFAULT_RATE : command.redeemRate(),
                    command.expiryDays() == null ? 0 : command.expiryDays(),
                    command.redeemCapPoints() == null ? 0L : command.redeemCapPoints(),
                    current.version(), command.idempotencyKey(), ACTIVE);
                // CAS 是真正的并发保护：上面的版本比对只覆盖"调用方带了版本号"的情况，
                // 而两个都不带版本号的并发写者会同时读到同一个 current，双双通过比对。
                // 受影响 0 行 = 期间已有他人写入，必须报冲突而不是覆盖。
                if (!repository.updateWithVersion(updated, expectedVersion)) {
                    throw new ApiException(409, "VERSION_CONFLICT", "积分规则已被其他请求修改");
                }
                saved = updated;
            }

            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .action("points.rule.save").resourceType("cst_point_rule_config")
                    .resourceId(String.valueOf(saved.id())).idempotencyKey(command.idempotencyKey())
                    .detailJson("{\"tenantId\":" + tenantId + ",\"businessType\":\"" + saved.businessType()
                            + "\",\"storeId\":" + storeId + "}").build());
            return saved;
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

    /** 业态归一：空白视为"未指定"（null），而非空串——两者的作用域语义不同。 */
    private static String normalize(String businessType) {
        return businessType == null || businessType.isBlank() ? null : businessType.trim().toUpperCase(Locale.ROOT);
    }

    public record SaveCommand(Long tenantId, Long storeId, String businessType, BigDecimal earnRate,
                              BigDecimal redeemRate, Integer expiryDays, Long redeemCapPoints,
                              Integer version, String idempotencyKey) {}

    public record PointRule(BigDecimal earnRate, BigDecimal redeemRate, int expiryDays, long redeemCapPoints,
                            int version, long id, String businessType, long storeId) {
        static final PointRule DEFAULT = new PointRule(DEFAULT_RATE, DEFAULT_RATE, 0, 0, 0, 0, "", 0);
        static PointRule from(PointRuleConfig config) {
            return new PointRule(config.earnRate(), config.redeemRate(), config.expiryDaysOrZero(),
                    config.redeemCapPointsOrZero(), config.currentVersion(),
                    config.id() == null ? 0L : config.id(), config.businessType(),
                    config.storeId() == null ? 0L : config.storeId());
        }
    }
}
