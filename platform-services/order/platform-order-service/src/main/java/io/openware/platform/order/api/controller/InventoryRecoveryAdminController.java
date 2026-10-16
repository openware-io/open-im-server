package io.openware.platform.order.api.controller;

import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.api.dto.OrderItemView;
import io.openware.platform.order.application.InventoryApplicationService;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.model.OrderItem;
import io.openware.platform.order.domain.order.repository.OrderItemQuery;
import io.openware.platform.order.domain.order.repository.OrderItemRepository;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/** 后台运营逐项处理作废订单的库存回补，和 C 端 business 会话严格隔离。 */
@RestController
@RequestMapping("/admin/orders")
public class InventoryRecoveryAdminController {
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final InventoryApplicationService inventoryService;
    private final AuditClient auditClient;

    @Autowired
    public InventoryRecoveryAdminController(OrderRepository orderRepository,
                                            OrderItemRepository orderItemRepository,
                                            InventoryApplicationService inventoryService, AuditClient auditClient) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.inventoryService = inventoryService;
        this.auditClient = auditClient;
    }

    /** 兼容既有装配：不传审计客户端时使用关闭态（生产装配始终注入真实客户端）。 */
    public InventoryRecoveryAdminController(OrderRepository orderRepository,
                                            OrderItemRepository orderItemRepository,
                                            InventoryApplicationService inventoryService) {
        this(orderRepository, orderItemRepository, inventoryService, AuditClient.disabled());
    }

    @GetMapping("/{id}/inventory-recovery")
    public List<OrderItemView> list(@PathVariable Long id) {
        PermissionGuard.require("inventory.recovery.view");
        Order order = requireOrder(id);
        if (!"VOIDED".equals(order.status())) {
            throw new BusinessException("ORDER_STATUS_INVALID", "仅作废订单可处理库存回补");
        }
        return orderItemRepository.list(OrderItemQuery.consumedOfOrder(order.tenantId(), id)).stream()
                .map(row -> OrderItemView.from(row.aggregate()))
                .toList();
    }

    @PostMapping("/{id}/items/{itemId}/inventory-recovery")
    @Transactional
    public OrderItemView decide(@PathVariable Long id, @PathVariable Long itemId,
                                @RequestBody RecoveryRequest request) {
        PermissionGuard.require("inventory.recovery.confirm");
        try {
            return doDecide(id, itemId, request);
        } catch (RuntimeException failure) {
            // 回补决定失败留痕（订单未作废/明细不存在/无需回补/库存落库失败）：与成功同码。
            recordFailure(id, itemId, failure);
            throw failure;
        }
    }

    private OrderItemView doDecide(Long id, Long itemId, RecoveryRequest request) {
        Order order = requireOrder(id);
        if (!"VOIDED".equals(order.status())) {
            throw new BusinessException("ORDER_STATUS_INVALID", "仅作废订单可处理库存回补");
        }
        OrderItem item = orderItemRepository.findById(itemId).orElse(null);
        if (item == null || !id.equals(item.orderId()) || !order.tenantId().equals(item.tenantId())) {
            throw new BusinessException("ORDER_ITEM_NOT_FOUND", "订单明细不存在");
        }
        if (!"CONSUMED".equals(item.inventoryStatus()) || item.inventoryMaterialId() == null) {
            throw new BusinessException("INVENTORY_RECOVERY_INVALID", "该明细无需回补或已处理");
        }
        boolean recover = Boolean.TRUE.equals(request.recover());
        String inventoryStatus;
        String decision;
        if (recover) {
            inventoryService.changeStock(item.inventoryMaterialId(), item.quantity(), "REVERSE", "ORDER_ITEM",
                    String.valueOf(itemId), request.reason(), "order-item:" + itemId + ":reverse");
            inventoryStatus = "REVERSED";
            decision = "RECOVERED";
        } else {
            inventoryStatus = "NOT_RECOVERED";
            decision = "NOT_RECOVERED";
        }
        TenantContext context = TenantContextHolder.get();
        // 领域模型不可变：决定结果返回新实例，落库用的是返回值。
        OrderItem decided = item.withInventoryRecovery(inventoryStatus, decision, request.reason(),
                context == null ? null : context.accountId(), LocalDateTime.now());
        orderItemRepository.update(decided);
        // 库存回补是「作废订单的补偿动作」：回补与不回补都必须留痕（含原因与决定人）。
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .action("order.recovery.confirm")
                .resourceType("ord_order_item").resourceId(String.valueOf(itemId))
                .resourceName(item.nameSnapshot())
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .idempotencyKey("order-item:" + itemId + ":recovery-decision")
                .detailJson("{\"orderId\":" + id + ",\"recovered\":" + recover + ",\"reason\":\""
                        + (request.reason() == null ? "" : request.reason().replace("\"", "\\\""))
                        + "\",\"inventoryMaterialId\":" + item.inventoryMaterialId() + "}")
                .build());
        return OrderItemView.from(decided);
    }

    private Order requireOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "订单不存在"));
    }

    /**
     * 库存回补决定的失败留痕：动作码与成功路径同码（{@code order.recovery.confirm}），
     * {@code result=FAILURE} + 稳定 errorCode。
     *
     * <p>审计只走 {@link AuditClient#recordAsync}（失败仅 WARN），业务异常原样抛出：留痕不改变业务结果。
     * 不带幂等键（重复失败各自留痕，也不覆盖成功路径的稳定键）；detail 只放订单/明细 ID。
     */
    private void recordFailure(Long orderId, Long itemId, RuntimeException failure) {
        TenantContext context = TenantContextHolder.get();
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(context == null ? null : context.tenantId())
                .storeId(context == null ? null : context.storeId())
                .action("order.recovery.confirm")
                .resourceType("ord_order_item")
                .resourceId(String.valueOf(itemId))
                .result(AuditClient.AuditRecord.RESULT_FAILURE)
                .errorCode(AuditErrorCodes.of(failure))
                .detailJson("{\"orderId\":" + orderId + ",\"itemId\":" + itemId + "}")
                .build());
    }

    public record RecoveryRequest(Boolean recover, String reason) {}
}
