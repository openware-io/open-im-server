package com.gvchat.platform.order.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gvchat.common.exception.BusinessException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.audit.AuditErrorCodes;
import com.gvchat.infrastructure.tenant.PermissionGuard;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.order.application.InventoryApplicationService;
import com.gvchat.platform.order.infra.persistence.mapper.OrderItemMapper;
import com.gvchat.platform.order.infra.persistence.mapper.OrderMapper;
import com.gvchat.platform.order.infra.persistence.po.OrderItemPo;
import com.gvchat.platform.order.infra.persistence.po.OrderPo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/** 后台运营逐项处理作废订单的库存回补，和 C 端 business 会话严格隔离。 */
@RestController
@RequestMapping("/admin/orders")
public class InventoryRecoveryAdminController {
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final InventoryApplicationService inventoryService;
    private final AuditClient auditClient;

    @Autowired
    public InventoryRecoveryAdminController(OrderMapper orderMapper, OrderItemMapper orderItemMapper,
                                            InventoryApplicationService inventoryService, AuditClient auditClient) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.inventoryService = inventoryService;
        this.auditClient = auditClient;
    }

    /** 兼容既有装配：不传审计客户端时使用关闭态（生产装配始终注入真实客户端）。 */
    public InventoryRecoveryAdminController(OrderMapper orderMapper, OrderItemMapper orderItemMapper,
                                            InventoryApplicationService inventoryService) {
        this(orderMapper, orderItemMapper, inventoryService, AuditClient.disabled());
    }

    @GetMapping("/{id}/inventory-recovery")
    public List<OrderItemPo> list(@PathVariable Long id) {
        PermissionGuard.require("inventory.recovery.view");
        OrderPo order = requireOrder(id);
        if (!"VOIDED".equals(order.getStatus())) {
            throw new BusinessException("ORDER_STATUS_INVALID", "仅作废订单可处理库存回补");
        }
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItemPo>()
                .eq(OrderItemPo::getTenantId, order.getTenantId()).eq(OrderItemPo::getOrderId, id)
                .eq(OrderItemPo::getInventoryStatus, "CONSUMED").orderByAsc(OrderItemPo::getId));
    }

    @PostMapping("/{id}/items/{itemId}/inventory-recovery")
    @Transactional
    public OrderItemPo decide(@PathVariable Long id, @PathVariable Long itemId,
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

    private OrderItemPo doDecide(Long id, Long itemId, RecoveryRequest request) {
        OrderPo order = requireOrder(id);
        if (!"VOIDED".equals(order.getStatus())) {
            throw new BusinessException("ORDER_STATUS_INVALID", "仅作废订单可处理库存回补");
        }
        OrderItemPo item = orderItemMapper.selectById(itemId);
        if (item == null || !id.equals(item.getOrderId()) || !order.getTenantId().equals(item.getTenantId())) {
            throw new BusinessException("ORDER_ITEM_NOT_FOUND", "订单明细不存在");
        }
        if (!"CONSUMED".equals(item.getInventoryStatus()) || item.getInventoryMaterialId() == null) {
            throw new BusinessException("INVENTORY_RECOVERY_INVALID", "该明细无需回补或已处理");
        }
        boolean recover = Boolean.TRUE.equals(request.recover());
        if (recover) {
            inventoryService.changeStock(item.getInventoryMaterialId(), item.getQuantity(), "REVERSE", "ORDER_ITEM",
                    String.valueOf(itemId), request.reason(), "order-item:" + itemId + ":reverse");
            item.setInventoryStatus("REVERSED");
            item.setInventoryRecoveryDecision("RECOVERED");
        } else {
            item.setInventoryStatus("NOT_RECOVERED");
            item.setInventoryRecoveryDecision("NOT_RECOVERED");
        }
        TenantContext context = TenantContextHolder.get();
        item.setInventoryRecoveryReason(request.reason());
        item.setInventoryRecoveryDecidedBy(context == null ? null : context.accountId());
        item.setInventoryRecoveryDecidedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        orderItemMapper.updateById(item);
        // 库存回补是「作废订单的补偿动作」：回补与不回补都必须留痕（含原因与决定人）。
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .action("order.recovery.confirm")
                .resourceType("ord_order_item").resourceId(String.valueOf(itemId))
                .resourceName(item.getNameSnapshot())
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .idempotencyKey("order-item:" + itemId + ":recovery-decision")
                .detailJson("{\"orderId\":" + id + ",\"recovered\":" + recover + ",\"reason\":\""
                        + (request.reason() == null ? "" : request.reason().replace("\"", "\\\""))
                        + "\",\"inventoryMaterialId\":" + item.getInventoryMaterialId() + "}")
                .build());
        return item;
    }

    private OrderPo requireOrder(Long id) {
        OrderPo order = orderMapper.selectById(id);
        if (order == null) throw new BusinessException("ORDER_NOT_FOUND", "订单不存在");
        return order;
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
