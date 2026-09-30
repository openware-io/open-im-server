package io.openware.common.payment.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.common.payment.application.PaymentRuleApplicationService;
import io.openware.infrastructure.security.InternalServiceAuthenticationFilter;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;

/** Payment P7-C2 内部 v1 配置端口；所有调用必须使用服务间签名。 */
@RestController
@RequestMapping("/internal/payment/rules")
public class PaymentRuleController {
    private final PaymentRuleApplicationService service;
    private final HttpServletRequest request;
    public PaymentRuleController(PaymentRuleApplicationService service, HttpServletRequest request) { this.service = service; this.request = request; }

    @GetMapping("/refund")
    public PaymentRuleApplicationService.RefundRuleView refund(@RequestParam(required = false) Long storeId,
                                                               @RequestParam(required = false) String businessType) {
        verify(); TenantContext c = context(); return service.resolveRefund(c.tenantId(), storeId, businessType);
    }

    @PutMapping("/refund")
    public RefundRuleConfig saveRefund(@RequestBody RefundRequest body) {
        verify(); TenantContext c = context(); var row = service.saveRefund(c.tenantId(), new PaymentRuleApplicationService.RefundSaveCommand(
                body.storeId(), body.businessType(), body.approvalThreshold(), body.offlineRefundEnabled(), body.version(), body.idempotencyKey()));
        return new RefundRuleConfig(row.getId(), row.getStoreId(), row.getBusinessType(), row.getApprovalThreshold(),
                Integer.valueOf(1).equals(row.getOfflineRefundEnabled()), row.getVersion());
    }

    @GetMapping("/daily-closing")
    public PaymentRuleApplicationService.ClosingRuleView closing(@RequestParam Long storeId) {
        verify(); TenantContext c = context(); return service.resolveClosing(c.tenantId(), storeId);
    }

    @PutMapping("/daily-closing")
    public ClosingRuleConfig saveClosing(@RequestBody ClosingRequest body) {
        verify(); TenantContext c = context(); var row = service.saveClosing(c.tenantId(), new PaymentRuleApplicationService.ClosingSaveCommand(
                body.storeId(), body.closingMinute(), body.version(), body.idempotencyKey()));
        return new ClosingRuleConfig(row.getId(), row.getStoreId(), row.getClosingMinute(), row.getVersion());
    }

    private TenantContext context() { TenantContext c = TenantContextHolder.get(); if (c == null || c.tenantId() <= 0) throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文"); return c; }
    private void verify() { if (!Boolean.TRUE.equals(request.getAttribute(InternalServiceAuthenticationFilter.AUTHENTICATED_ATTRIBUTE))) throw new ApiException(401, "INVALID_INTERNAL_SERVICE_AUTHENTICATION", "内部服务鉴权失败"); }
    public record RefundRequest(Long storeId, String businessType, BigDecimal approvalThreshold, Boolean offlineRefundEnabled, Integer version, String idempotencyKey) {}
    public record ClosingRequest(Long storeId, Integer closingMinute, Integer version, String idempotencyKey) {}
    public record RefundRuleConfig(Long id, Long storeId, String businessType, BigDecimal approvalThreshold,
                                   boolean offlineRefundEnabled, Integer version) {}
    public record ClosingRuleConfig(Long id, Long storeId, Integer closingMinute, Integer version) {}
}
