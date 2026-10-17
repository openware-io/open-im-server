package io.openware.platform.order.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.dto.BillResult;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.repository.MemberLookupProvider;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客户消费账单的访问控制 + 构建入口。
 *
 * <p>授权口径（2026-09-19 修复越权）：持 {@code order.view}（收银员/店长/财务/服务人员等 B 端角色）
 * 的行为完全不变；未持有的 A380 C 端消费者会话只允许**本人**订单
 * （{@code account_id → cst_member.id → ord_order.customer_id}），他人订单 403
 * {@code ORDER_SCOPE_DENIED}；解析不出会员档案保持既有 403 {@code PERMISSION_DENIED} 文案。
 *
 * <p>控制器只做协议转换（standards/10 §应用服务规则）；访问控制与账单构建都在应用服务。
 */
@Service
@RequiredArgsConstructor
public class OrderBillAccessService {
  /** 商户侧「查看订单/账单」权限码（与订单明细读路径同码）。 */
  private static final String PERMISSION_VIEW_ORDER = "order.view";

  private final BillApplicationService billService;
  private final OrderRepository orderRepository;
  private final MemberLookupProvider memberLookupProvider;

  @Transactional(readOnly = true)
  public BillResult bill(Long orderId) {
    requireBillAccess(orderId);
    return billService.buildBill(orderId);
  }

  /** 商户权限码优先；消费者会话只放行本人订单。 */
  private void requireBillAccess(Long orderId) {
    if (hasPermission(PERMISSION_VIEW_ORDER)) {
      return;
    }
    Order order = orderRepository.findById(orderId).orElse(null);
    TenantContext context = TenantContextHolder.get();
    Long memberId = context == null || memberLookupProvider == null
        ? null
        : memberLookupProvider.findMemberId(context.tenantId(), context.accountId()).orElse(null);
    if (memberId == null) {
      // 既没有商户权限也不是会员：与其它自助端点保持同一文案（不泄露订单是否存在）。
      throw new ApiException(403, "PERMISSION_DENIED", "缺少权限: " + PERMISSION_VIEW_ORDER);
    }
    if (order == null || order.customerId() == null || !memberId.equals(order.customerId())) {
      throw new ApiException(403, "ORDER_SCOPE_DENIED", "只能查看和操作本人订单");
    }
  }

  /** 当前签名上下文是否持有权限码（消费端/B 端权限都来自签名 token，客户端无法伪造）。 */
  private static boolean hasPermission(String permissionCode) {
    TenantContext context = TenantContextHolder.get();
    return context != null && context.permissions() != null
        && context.permissions().contains(permissionCode);
  }
}
