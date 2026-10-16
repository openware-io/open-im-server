package io.openware.platform.order.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.api.dto.OrderView;
import io.openware.platform.order.api.dto.ReservationView;
import io.openware.platform.order.application.ReservationApplicationService;
import io.openware.platform.order.domain.order.port.MemberLookupProvider;
import io.openware.platform.order.domain.order.repository.OrderQuery;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consumer order history. Queries are constrained by the authenticated account's member ID.
 *
 * <p>订单走仓储端口（应用层读路径）：条件只表达「按会员 + 按创建时间倒序」，SQL 由适配器落成；
 * 响应是 {@link OrderView}（字段名与原持久化对象一致）。
 */
@RestController
@RequestMapping("/me")
public class MyOrderController {
  private final MemberLookupProvider memberLookupProvider;
  private final OrderRepository orderRepository;
  private final ReservationApplicationService reservationService;

  public MyOrderController(MemberLookupProvider memberLookupProvider, OrderRepository orderRepository,
                           ReservationApplicationService reservationService) {
    this.memberLookupProvider = memberLookupProvider;
    this.orderRepository = orderRepository;
    this.reservationService = reservationService;
  }

  /** 我的订单（C 端）：按会员收窄，创建时间倒序。 */
  @GetMapping("/orders")
  public List<OrderView> orders() {
    return orderRepository.list(OrderQuery.newestFirst().withCustomer(memberId())).stream()
        .map(row -> OrderView.from(row.aggregate()))
        .toList();
  }

  /**
   * 我的预约（C 端）：预约按房型创建，这里回填房型名/编码与已分配包厢名，
   * 前端展示「包厢类型（房型）+ 到店后由门店分配包厢」；历史预约（只有包厢）回退显示旧包厢名。
   *
   * <p>查询与展示装配都在应用服务（`listMine`）：本控制器不再直接使用预约的 Mapper/PO，
   * 响应是 {@link ReservationView}（字段名与原持久化对象一致）。
   */
  @GetMapping("/reservations")
  public List<ReservationView> reservations() {
    return reservationService.listMine(memberId());
  }

  private long memberId() {
    TenantContext context = TenantContextHolder.get();
    if (context == null) {
      throw new ApiException(401, "ACCOUNT_REQUIRED", "缺少已认证账号");
    }
    // account_id → 会员 id 走 order 域自己的只读端口（跨上下文只读依赖由调用方拥有）。
    Long memberId = memberLookupProvider.findMemberId(context.tenantId(), context.accountId()).orElse(null);
    if (memberId == null) {
      throw new ApiException(404, "MEMBER_NOT_FOUND", "会员档案尚未初始化");
    }
    return memberId;
  }
}
