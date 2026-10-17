package io.openware.platform.order.api.controller;

import io.openware.platform.order.api.dto.OrderItemView;
import io.openware.platform.order.application.InventoryRecoveryApplicationService;
import io.openware.platform.order.application.InventoryRecoveryApplicationService.InventoryRecoveryDecisionCommand;
import io.openware.platform.order.domain.order.model.OrderItem;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/**
 * 后台运营逐项处理作废订单的库存回补，和 C 端 business 会话严格隔离。
 *
 * <p>权限、订单/明细读取、库存变更、落库与审计都在
 * {@link InventoryRecoveryApplicationService}；本类只做协议转换。
 */
@RestController
@RequestMapping("/admin/orders")
public class InventoryRecoveryAdminController {
    private final InventoryRecoveryApplicationService inventoryRecoveryService;

    public InventoryRecoveryAdminController(InventoryRecoveryApplicationService inventoryRecoveryService) {
        this.inventoryRecoveryService = inventoryRecoveryService;
    }

    @GetMapping("/{id}/inventory-recovery")
    public List<OrderItemView> list(@PathVariable Long id) {
        return inventoryRecoveryService.listPending(id).stream().map(OrderItemView::from).toList();
    }

    @PostMapping("/{id}/items/{itemId}/inventory-recovery")
    public OrderItemView decide(@PathVariable Long id, @PathVariable Long itemId,
                                @RequestBody RecoveryRequest request) {
        OrderItem decided = inventoryRecoveryService.decide(id, itemId,
                new InventoryRecoveryDecisionCommand(request.recover(), request.reason()));
        return OrderItemView.from(decided);
    }

    public record RecoveryRequest(Boolean recover, String reason) {}
}
