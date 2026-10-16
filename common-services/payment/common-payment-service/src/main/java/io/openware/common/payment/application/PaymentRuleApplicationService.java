package io.openware.common.payment.application;

import io.openware.common.exception.ApiException;
import io.openware.common.payment.domain.rule.model.ClosingRuleConfig;
import io.openware.common.payment.domain.rule.model.RefundRuleConfig;
import io.openware.common.payment.domain.rule.repository.PaymentRuleRepository;
import io.openware.common.payment.domain.rule.service.RefundRuleResolver;
import io.openware.infrastructure.audit.AuditClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.Locale;

/** Payment 域 P7-C2 配置端口，负责作用域解析、写入幂等和版本冲突。 */
@Service
public class PaymentRuleApplicationService {
    private final PaymentRuleRepository ruleRepository;
    private final AuditClient auditClient;

    public PaymentRuleApplicationService(PaymentRuleRepository ruleRepository, AuditClient auditClient) {
        this.ruleRepository = ruleRepository;
        this.auditClient = auditClient;
    }

    /**
     * 解析生效的退款规则。
     *
     * <p>三条候选行分别按自己的键查询后交给 {@link RefundRuleResolver}：层间顺序与"门店行不决定
     * 线下退款开关"这两条规则收在领域里，本方法只负责取值。
     */
    public RefundRuleView resolveRefund(Long tenantId, Long storeId, String businessType) {
        requireTenant(tenantId);
        String type = normalize(businessType);
        RefundRuleConfig storeRow = storeId == null ? null
                : ruleRepository.findRefundRule(tenantId, type, storeId).orElse(null);
        RefundRuleConfig businessRow = type == null ? null
                : ruleRepository.findRefundRule(tenantId, type, null).orElse(null);
        RefundRuleConfig tenantRow = ruleRepository
                .findRefundRule(tenantId, RefundRuleConfig.ANY_BUSINESS_TYPE, null).orElse(null);
        RefundRuleResolver.ResolvedRefundRule resolved = RefundRuleResolver.resolve(
                java.util.Optional.ofNullable(storeRow),
                java.util.Optional.ofNullable(businessRow),
                java.util.Optional.ofNullable(tenantRow));
        return new RefundRuleView(resolved.approvalThreshold(), resolved.offlineRefundEnabled(),
                resolved.source(), resolved.version());
    }

    public ClosingRuleView resolveClosing(Long tenantId, Long storeId) {
        requireTenant(tenantId);
        if (storeId == null || storeId <= 0) {
            throw new ApiException(400, "STORE_CONTEXT_REQUIRED", "日结规则必须指定门店");
        }
        return ruleRepository.findClosingRule(tenantId, storeId)
                .map(row -> new ClosingRuleView(row.closingMinute(), "STORE", row.version()))
                .orElseGet(() -> new ClosingRuleView(0, "DEFAULT", 0));
    }

    @Transactional
    public RefundRuleConfig saveRefund(Long tenantId, RefundSaveCommand c) {
        requireTenant(tenantId);
        String type = normalize(c.businessType());
        Long store = c.storeId() == null || c.storeId() <= 0 ? null : c.storeId();
        if (store != null && type == null) {
            throw new ApiException(400, "BUSINESS_TYPE_REQUIRED", "门店规则必须指定业态");
        }
        RefundRuleConfig existing = ruleRepository.findRefundRule(tenantId, type, store).orElse(null);
        if (existing != null && c.version() != null && !c.version().equals(existing.version())) {
            throw new ApiException(409, "VERSION_CONFLICT", "退款规则版本冲突");
        }
        BigDecimal threshold = c.approvalThreshold() == null ? BigDecimal.ZERO : c.approvalThreshold();
        // 线下退款只允许租户/业态默认；门店行保留统一安全默认（开启），解析时从上层继承。
        Boolean offline = store == null
                ? Boolean.TRUE.equals(c.offlineRefundEnabled())
                : (existing == null || existing.offlineRefundEnabled() == null
                    ? Boolean.TRUE : existing.offlineRefundEnabled());
        RefundRuleConfig draft = new RefundRuleConfig(existing == null ? null : existing.id(), tenantId,
                type == null ? RefundRuleConfig.ANY_BUSINESS_TYPE : type, store, threshold, offline,
                existing == null ? 0 : existing.version(), c.idempotencyKey(),
                RefundRuleConfig.STATUS_ACTIVE);
        RefundRuleConfig saved;
        if (existing == null) {
            saved = ruleRepository.insertRefundRule(draft);
        } else {
            updateOrConflict(ruleRepository.updateRefundRule(draft, existing.version()));
            saved = draft;
        }
        auditClient.recordAsync(AuditClient.AuditRecord.builder().tenantId(tenantId).storeId(store)
                .action("payment.refund.rule.save").resourceType("pay_refund_rule_config")
                .resourceId(String.valueOf(saved.id())).build());
        return saved;
    }

    @Transactional
    public ClosingRuleConfig saveClosing(Long tenantId, ClosingSaveCommand c) {
        requireTenant(tenantId);
        if (c.storeId() == null || c.storeId() <= 0) {
            throw new ApiException(400, "STORE_CONTEXT_REQUIRED", "日结规则必须指定门店");
        }
        if (!ClosingRuleConfig.validClosingMinute(c.closingMinute())) {
            throw new ApiException(400, "CLOSING_MINUTE_INVALID", "日结时间必须在 0 到 1439 分钟内");
        }
        ClosingRuleConfig existing = ruleRepository.findClosingRule(tenantId, c.storeId()).orElse(null);
        if (existing != null && c.version() != null && !c.version().equals(existing.version())) {
            throw new ApiException(409, "VERSION_CONFLICT", "日结规则版本冲突");
        }
        ClosingRuleConfig draft = new ClosingRuleConfig(existing == null ? null : existing.id(), tenantId,
                c.storeId(), c.closingMinute(), existing == null ? 0 : existing.version(),
                c.idempotencyKey(), ClosingRuleConfig.STATUS_ACTIVE);
        ClosingRuleConfig saved;
        if (existing == null) {
            saved = ruleRepository.insertClosingRule(draft);
        } else {
            updateOrConflict(ruleRepository.updateClosingRule(draft, existing.version()));
            saved = draft;
        }
        auditClient.recordAsync(AuditClient.AuditRecord.builder().tenantId(tenantId).storeId(c.storeId())
                .action("payment.daily-closing.rule.save").resourceType("pay_daily_closing_rule_config")
                .resourceId(String.valueOf(saved.id())).build());
        return saved;
    }

    /**
     * CAS 冲突翻译。
     *
     * <p>上一步的版本比对只是快速失败；**真正的并发判定在端口那条带版本条件的 UPDATE 里**
     * （受影响 0 行即冲突）。先读出来比对再无条件写回会让两个并发请求双双通过比对。
     */
    private static void updateOrConflict(boolean updated) {
        if (!updated) {
            throw new ApiException(409, "VERSION_CONFLICT", "规则已被其他请求修改");
        }
    }

    private static void requireTenant(Long id) {
        if (id == null || id <= 0) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
    }

    private static String normalize(String s) {
        return blank(s) ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    public record RefundSaveCommand(Long storeId, String businessType, BigDecimal approvalThreshold,
                                    Boolean offlineRefundEnabled, Integer version, String idempotencyKey) {}
    public record ClosingSaveCommand(Long storeId, Integer closingMinute, Integer version, String idempotencyKey) {}
    public record RefundRuleView(BigDecimal approvalThreshold, boolean offlineRefundEnabled, String source,
                                 Integer version) {}
    public record ClosingRuleView(Integer closingMinute, String source, Integer version) {}
}
