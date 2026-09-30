package io.openware.platform.order.api.controller;

import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.common.exception.ApiException;
import io.openware.platform.order.application.OrderVoidApprovalApplicationService;
import io.openware.platform.order.infra.persistence.po.OrdOrderVoidApprovalPo;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** v1 订单作废申请/审批 API；真实订单写入仍由 Order 域应用服务执行。 */
@RestController
@RequestMapping("/business/orders/void-approvals")
public class OrderVoidApprovalController {
    private final OrderVoidApprovalApplicationService service;
    public OrderVoidApprovalController(OrderVoidApprovalApplicationService service) { this.service = service; }

    @PostMapping("/{orderId}/request")
    public ApprovalResponse request(@PathVariable Long orderId, @RequestBody VoidApprovalRequest body) {
        PermissionGuard.require("order.void");
        TenantContext c = context();
        return response(service.submit(c.tenantId(), c.storeId(), c.accountId(), orderId,
                body == null ? null : body.reason(), body == null ? null : body.idempotencyKey()));
    }

    @GetMapping
    public List<ApprovalResponse> list(@RequestParam(required = false) String status) {
        PermissionGuard.require("order.void"); TenantContext c = context();
        return service.list(c.tenantId(), c.storeId(), status).stream().map(OrderVoidApprovalController::response).toList();
    }

    @GetMapping("/{id}")
    public ApprovalResponse get(@PathVariable Long id) {
        PermissionGuard.require("order.void"); TenantContext c = context();
        return response(service.get(c.tenantId(), c.storeId(), id));
    }

    @PostMapping("/{id}/approve")
    public ApprovalResponse approve(@PathVariable Long id, @RequestBody(required = false) ReviewRequest body) {
        PermissionGuard.require("order.void"); TenantContext c = context();
        return response(service.approve(c.tenantId(), c.storeId(), c.accountId(), id, body == null ? null : body.comment()));
    }

    @PostMapping("/{id}/reject")
    public ApprovalResponse reject(@PathVariable Long id, @RequestBody(required = false) ReviewRequest body) {
        PermissionGuard.require("order.void"); TenantContext c = context();
        return response(service.reject(c.tenantId(), c.storeId(), c.accountId(), id, body == null ? null : body.comment()));
    }

    private static TenantContext context() {
        TenantContext c = TenantContextHolder.get();
        if (c == null || c.tenantId() <= 0 || c.storeId() == null)
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少有效的租户/门店上下文");
        return c;
    }
    public record VoidApprovalRequest(String reason, String idempotencyKey) {}
    public record ReviewRequest(String comment) {}
    public record ApprovalResponse(Long id, Long tenantId, Long storeId, Long orderId, Integer orderVersion,
                                   String orderStatusSnapshot, String reason, String status, Long applicantId,
                                   Long approverId, String reviewComment, String idempotencyKey, Integer version,
                                   java.time.LocalDateTime createdAt, java.time.LocalDateTime updatedAt,
                                   java.time.LocalDateTime reviewedAt) {}
    private static ApprovalResponse response(OrdOrderVoidApprovalPo p) {
        return new ApprovalResponse(p.getId(), p.getTenantId(), p.getStoreId(), p.getOrderId(), p.getOrderVersion(),
                p.getOrderStatusSnapshot(), p.getReason(), p.getStatus(), p.getApplicantId(), p.getApproverId(),
                p.getReviewComment(), p.getIdempotencyKey(), p.getVersion(), p.getCreatedAt(), p.getUpdatedAt(), p.getReviewedAt());
    }
}
