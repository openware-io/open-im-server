package io.openware.platform.order.application;

import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.model.OrderItem;
import io.openware.platform.order.domain.order.repository.OrderItemQuery;
import io.openware.platform.order.domain.order.repository.OrderItemRepository;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.domain.order.repository.Persisted;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台作废订单的库存回补编排。
 *
 * <p>权限校验、订单/明细读取、库存回补、明细落库与审计留痕都在应用服务内完成；
 * 控制器只做协议转换（standards/10 §应用服务规则）。成功与失败都必须留痕，
 * 且失败留痕不改变业务结果。
 */
@Service
@RequiredArgsConstructor
public class InventoryRecoveryApplicationService {
  private final OrderRepository orderRepository;
  private final OrderItemRepository orderItemRepository;
  private final InventoryApplicationService inventoryService;
  private final AuditClient auditClient;

  /** 待回补明细：仅作废订单可查看。 */
  @Transactional(readOnly = true)
  public List<OrderItem> listPending(Long orderId) {
    PermissionGuard.require("inventory.recovery.view");
    Order order = requireVoidedOrder(orderId);
    return orderItemRepository.list(OrderItemQuery.consumedOfOrder(order.tenantId(), orderId)).stream()
        .map(Persisted::aggregate)
        .toList();
  }

  /** 处理单条明细：回补或不回补都写决定并留痕。 */
  @Transactional
  public OrderItem decide(Long orderId, Long itemId, InventoryRecoveryDecisionCommand command) {
    PermissionGuard.require("inventory.recovery.confirm");
    try {
      return doDecide(orderId, itemId, command);
    } catch (RuntimeException failure) {
      // 回补决定失败留痕（订单未作废/明细不存在/无需回补/库存落库失败）：与成功同码。
      recordFailure(orderId, itemId, failure);
      throw failure;
    }
  }

  private OrderItem doDecide(Long orderId, Long itemId, InventoryRecoveryDecisionCommand command) {
    Order order = requireVoidedOrder(orderId);
    OrderItem item = orderItemRepository.findById(itemId).orElse(null);
    if (item == null || !orderId.equals(item.orderId()) || !order.tenantId().equals(item.tenantId())) {
      throw new BusinessException("ORDER_ITEM_NOT_FOUND", "订单明细不存在");
    }
    if (!"CONSUMED".equals(item.inventoryStatus()) || item.inventoryMaterialId() == null) {
      throw new BusinessException("INVENTORY_RECOVERY_INVALID", "该明细无需回补或已处理");
    }
    boolean recover = Boolean.TRUE.equals(command.recover());
    String inventoryStatus;
    String decision;
    if (recover) {
      inventoryService.changeStock(item.inventoryMaterialId(), item.quantity(), "REVERSE", "ORDER_ITEM",
          String.valueOf(itemId), command.reason(), "order-item:" + itemId + ":reverse");
      inventoryStatus = "REVERSED";
      decision = "RECOVERED";
    } else {
      inventoryStatus = "NOT_RECOVERED";
      decision = "NOT_RECOVERED";
    }
    TenantContext context = TenantContextHolder.get();
    // 领域模型不可变：决定结果返回新实例，落库用的是返回值。
    OrderItem decided = item.withInventoryRecovery(inventoryStatus, decision, command.reason(),
        context == null ? null : context.accountId(), LocalDateTime.now());
    orderItemRepository.update(decided);
    // 库存回补是「作废订单的补偿动作」：回补与不回补都必须留痕（含原因与决定人）。
    auditClient.recordAsync(AuditClient.AuditRecord.builder()
        .action("order.recovery.confirm")
        .resourceType("ord_order_item").resourceId(String.valueOf(itemId))
        .resourceName(item.nameSnapshot())
        .result(AuditClient.AuditRecord.RESULT_SUCCESS)
        .idempotencyKey("order-item:" + itemId + ":recovery-decision")
        .detailJson("{\"orderId\":" + orderId + ",\"recovered\":" + recover + ",\"reason\":\""
            + (command.reason() == null ? "" : command.reason().replace("\"", "\\\""))
            + "\",\"inventoryMaterialId\":" + item.inventoryMaterialId() + "}")
        .build());
    return decided;
  }

  private Order requireVoidedOrder(Long orderId) {
    Order order = orderRepository.findById(orderId)
        .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "订单不存在"));
    if (!"VOIDED".equals(order.status())) {
      throw new BusinessException("ORDER_STATUS_INVALID", "仅作废订单可处理库存回补");
    }
    return order;
  }

  /**
   * 库存回补决定的失败留痕：动作码与成功路径同码，{@code result=FAILURE} + 稳定 errorCode。
   *
   * <p>不带幂等键（重复失败各自留痕，也不覆盖成功路径的稳定键）；detail 只放订单/明细 ID。
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

  /** 回补决定的应用层命令（避免控制器 DTO 渗透到服务）。 */
  public record InventoryRecoveryDecisionCommand(Boolean recover, String reason) {
  }
}
