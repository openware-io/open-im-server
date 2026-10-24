package io.openware.platform.order.api.controller;

import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.common.exception.ApiException;
import io.openware.platform.order.application.OrderVoidApprovalApplicationService;
import io.openware.platform.order.domain.order.model.OrderVoidApproval;
import io.openware.platform.order.domain.order.repository.AuditSnapshot;
import io.openware.platform.order.domain.order.repository.Persisted;
import io.openware.infrastructure.tenant.ClientAudience;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** v1 订单作废申请/审批 API；真实订单写入仍由 Order 域应用服务执行。 */
@ClientAudience("saas-b")
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
        return service.list(c.tenantId(), c.storeId(), status).stream()
                .map(OrderVoidApprovalController::response).toList();
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

    /**
     * 领域聚合 + 记账快照 → 响应。
     *
     * <p>{@code createdAt}/{@code updatedAt} 是记账列（不进领域模型），由适配器随行读回；
     * 没有快照时（理论上不会发生）取 {@code null} 而不是假装有值。
     */
    private static ApprovalResponse response(Persisted<OrderVoidApproval> row) {
        OrderVoidApproval p = row.aggregate();
        AuditSnapshot audit = row.audit();
        return new ApprovalResponse(p.id(), p.tenantId(), p.storeId(), p.orderId(), p.orderVersion(),
                p.orderStatusSnapshot(), p.reason(), p.status(), p.applicantId(), p.approverId(),
                p.reviewComment(), p.idempotencyKey(), p.version(),
                audit == null ? null : audit.createdAt(), audit == null ? null : audit.updatedAt(),
                p.reviewedAt());
    }
}
