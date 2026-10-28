package io.openware.platform.order.api.controller;

import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.platform.order.api.dto.OrderItemView;
import io.openware.platform.order.application.dto.OrderView;
import io.openware.platform.order.application.OrderItemApplicationService;
import io.openware.platform.order.application.OrderItemApplicationService.AddItemCommand;
import io.openware.platform.order.application.SettlementApplicationService;
import io.openware.infrastructure.tenant.ClientAudience;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单加项端点：加项、列表、确认、拒绝、结算。
 *
 * <p>授权、目录项价目快照、库存占用、金额同步、待确认缓存失效与审计都在
 * {@link OrderItemApplicationService}；结算委托 {@link SettlementApplicationService}。
 * 本类只做协议转换。
 */
@ClientAudience("saas-b")
@RestController
@RequestMapping("/business/orders/{orderId}")
public class OrderItemController {

    private final OrderItemApplicationService orderItemService;
    private final SettlementApplicationService settlementService;

    public OrderItemController(OrderItemApplicationService orderItemService,
                               SettlementApplicationService settlementService) {
        this.orderItemService = orderItemService;
        this.settlementService = settlementService;
    }

    /**
     * 加项（加钟/加商品/加服务）。
     * 服务端按目录项快照名称/单价/单位回填，防止客户端篡改价格。
     */
    @PostMapping("/items")
    public OrderItemView addItem(@PathVariable Long orderId, @RequestBody AddItemRequest req) {
        return OrderItemView.from(orderItemService.addItem(orderId, new AddItemCommand(
                req.tenantId(), req.itemType(), req.catalogItemId(), req.resourceId(),
                req.name(), req.unitPrice(), req.quantity(), req.source())));
    }

    /** 订单加项列表（B 端查看待确认加项；C 端消费者查看本人加项）。 */
    @GetMapping("/items")
    public List<OrderItemView> listItems(@PathVariable Long orderId) {
        return orderItemService.listItems(orderId).stream().map(OrderItemView::from).toList();
    }

    /** 确认加项：PENDING_APPROVAL → ACTIVE（原子条件更新，重复确认幂等）。 */
    @PostMapping("/items/{itemId}/confirm")
    public OrderItemView confirm(@PathVariable Long orderId, @PathVariable Long itemId) {
        return OrderItemView.from(orderItemService.confirm(orderId, itemId));
    }

    /** 拒绝加项：PENDING_APPROVAL → REJECTED（原子条件更新，重复拒绝幂等）。 */
    @PostMapping("/items/{itemId}/reject")
    public OrderItemView reject(@PathVariable Long orderId, @PathVariable Long itemId) {
        return OrderItemView.from(orderItemService.reject(orderId, itemId));
    }

    /** 结算：服务端汇总明细固化金额快照。 */
    @PostMapping("/settle")
    public OrderView settle(@PathVariable Long orderId, @RequestBody SettleRequest req) {
        // 结算权限码仍由端点守卫：SettlementApplicationService 也被其它调用方复用，不在服务内重复鉴权。
        PermissionGuard.require("order.settle");
        return OrderView.from(settlementService.settle(orderId, req.expectedVersion()));
    }

    public record AddItemRequest(Long tenantId, String itemType, Long catalogItemId, Long resourceId,
                                 String name, BigDecimal unitPrice, BigDecimal quantity, String source) {}
    public record SettleRequest(int expectedVersion) {}
}
