package io.openware.common.payment.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.common.payment.infra.persistence.mapper.PayDailyClosingRuleConfigMapper;
import io.openware.common.payment.infra.persistence.mapper.PayRefundRuleConfigMapper;
import io.openware.common.payment.infra.persistence.po.PayDailyClosingRuleConfigPo;
import io.openware.common.payment.infra.persistence.po.PayRefundRuleConfigPo;
import io.openware.infrastructure.audit.AuditClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

/** Payment 域 P7-C2 配置端口，负责作用域解析、写入幂等和版本冲突。 */
@Service
public class PaymentRuleApplicationService {
    private final PayRefundRuleConfigMapper refundMapper;
    private final PayDailyClosingRuleConfigMapper closingMapper;
    private final AuditClient auditClient;

    public PaymentRuleApplicationService(PayRefundRuleConfigMapper refundMapper,
                                         PayDailyClosingRuleConfigMapper closingMapper, AuditClient auditClient) {
        this.refundMapper = refundMapper;
        this.closingMapper = closingMapper;
        this.auditClient = auditClient;
    }

    public RefundRuleView resolveRefund(Long tenantId, Long storeId, String businessType) {
        requireTenant(tenantId);
        String type = normalize(businessType);
        PayRefundRuleConfigPo row = storeId == null ? null : findRefund(tenantId, type, storeId);
        if (row == null && type != null) row = findRefund(tenantId, type, null);
        if (row == null) row = findRefund(tenantId, "", null);
        return row == null ? new RefundRuleView(BigDecimal.ZERO, true, "DEFAULT")
                : new RefundRuleView(row.getApprovalThreshold(), Integer.valueOf(1).equals(row.getOfflineRefundEnabled()),
                row.getStoreId() != null ? "STORE" : (blank(row.getBusinessType()) ? "TENANT" : "BUSINESS"));
    }

    public ClosingRuleView resolveClosing(Long tenantId, Long storeId) {
        requireTenant(tenantId);
        if (storeId == null || storeId <= 0) throw new ApiException(400, "STORE_CONTEXT_REQUIRED", "日结规则必须指定门店");
        PayDailyClosingRuleConfigPo row = closingMapper.selectOne(new LambdaQueryWrapper<PayDailyClosingRuleConfigPo>()
                .eq(PayDailyClosingRuleConfigPo::getTenantId, tenantId).eq(PayDailyClosingRuleConfigPo::getStoreId, storeId)
                .eq(PayDailyClosingRuleConfigPo::getStatus, "ACTIVE").last("LIMIT 1"));
        return row == null ? new ClosingRuleView(0, "DEFAULT", 0) : new ClosingRuleView(row.getClosingMinute(), "STORE", row.getVersion());
    }

    @Transactional
    public PayRefundRuleConfigPo saveRefund(Long tenantId, RefundSaveCommand c) {
        requireTenant(tenantId);
        String type = normalize(c.businessType());
        Long store = c.storeId() == null || c.storeId() <= 0 ? null : c.storeId();
        if (store != null && type == null) throw new ApiException(400, "BUSINESS_TYPE_REQUIRED", "门店规则必须指定业态");
        PayRefundRuleConfigPo row = refundMapper.selectOne(new LambdaQueryWrapper<PayRefundRuleConfigPo>().eq(PayRefundRuleConfigPo::getTenantId, tenantId)
                .eq(PayRefundRuleConfigPo::getBusinessType, type == null ? "" : type).eq(PayRefundRuleConfigPo::getStoreId, store).last("LIMIT 1"));
        if (row != null && c.version() != null && !c.version().equals(row.getVersion())) throw new ApiException(409, "VERSION_CONFLICT", "退款规则版本冲突");
        LocalDateTime now = LocalDateTime.now();
        if (row == null) { row = new PayRefundRuleConfigPo(); row.setTenantId(tenantId); row.setBusinessType(type == null ? "" : type); row.setStoreId(store); row.setVersion(0); row.setCreatedAt(now); row.setCreatedBy(0L); }
        row.setApprovalThreshold(c.approvalThreshold() == null ? BigDecimal.ZERO : c.approvalThreshold());
        row.setOfflineRefundEnabled(Boolean.TRUE.equals(c.offlineRefundEnabled()) ? 1 : 0); row.setStatus("ACTIVE"); row.setIdempotencyKey(c.idempotencyKey()); row.setUpdatedAt(now); row.setUpdatedBy(0L);
        if (row.getId() == null) refundMapper.insert(row); else { row.setVersion(row.getVersion() + 1); refundMapper.updateById(row); }
        auditClient.recordAsync(AuditClient.AuditRecord.builder().tenantId(tenantId).storeId(store).action("payment.refund.rule.save").resourceType("pay_refund_rule_config").resourceId(String.valueOf(row.getId())).build());
        return row;
    }

    @Transactional
    public PayDailyClosingRuleConfigPo saveClosing(Long tenantId, ClosingSaveCommand c) {
        requireTenant(tenantId);
        if (c.storeId() == null || c.storeId() <= 0) throw new ApiException(400, "STORE_CONTEXT_REQUIRED", "日结规则必须指定门店");
        if (c.closingMinute() == null || c.closingMinute() < 0 || c.closingMinute() > 1439) throw new ApiException(400, "CLOSING_MINUTE_INVALID", "日结时间必须在 0 到 1439 分钟内");
        PayDailyClosingRuleConfigPo row = closingMapper.selectOne(new LambdaQueryWrapper<PayDailyClosingRuleConfigPo>().eq(PayDailyClosingRuleConfigPo::getTenantId, tenantId).eq(PayDailyClosingRuleConfigPo::getStoreId, c.storeId()).last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (row == null) { row = new PayDailyClosingRuleConfigPo(); row.setTenantId(tenantId); row.setStoreId(c.storeId()); row.setVersion(0); row.setCreatedAt(now); row.setCreatedBy(0L); }
        if (row.getId() != null && c.version() != null && !c.version().equals(row.getVersion())) throw new ApiException(409, "VERSION_CONFLICT", "日结规则版本冲突");
        row.setClosingMinute(c.closingMinute()); row.setStatus("ACTIVE"); row.setIdempotencyKey(c.idempotencyKey()); row.setUpdatedAt(now); row.setUpdatedBy(0L);
        if (row.getId() == null) closingMapper.insert(row); else { row.setVersion(row.getVersion() + 1); closingMapper.updateById(row); }
        auditClient.recordAsync(AuditClient.AuditRecord.builder().tenantId(tenantId).storeId(c.storeId()).action("payment.daily-closing.rule.save").resourceType("pay_daily_closing_rule_config").resourceId(String.valueOf(row.getId())).build());
        return row;
    }

    private PayRefundRuleConfigPo findRefund(Long tenantId, String type, Long store) {
        LambdaQueryWrapper<PayRefundRuleConfigPo> query = new LambdaQueryWrapper<PayRefundRuleConfigPo>()
                .eq(PayRefundRuleConfigPo::getTenantId, tenantId)
                .eq(PayRefundRuleConfigPo::getBusinessType, type == null ? "" : type)
                .eq(PayRefundRuleConfigPo::getStatus, "ACTIVE").last("LIMIT 1");
        if (store == null) query.isNull(PayRefundRuleConfigPo::getStoreId);
        else query.eq(PayRefundRuleConfigPo::getStoreId, store);
        return refundMapper.selectOne(query);
    }
    private static void requireTenant(Long id) { if (id == null || id <= 0) throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文"); }
    private static String normalize(String s) { return blank(s) ? null : s.trim().toUpperCase(Locale.ROOT); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    public record RefundSaveCommand(Long storeId, String businessType, BigDecimal approvalThreshold, Boolean offlineRefundEnabled, Integer version, String idempotencyKey) {}
    public record ClosingSaveCommand(Long storeId, Integer closingMinute, Integer version, String idempotencyKey) {}
    public record RefundRuleView(BigDecimal approvalThreshold, boolean offlineRefundEnabled, String source) {}
    public record ClosingRuleView(Integer closingMinute, String source, Integer version) {}
}
