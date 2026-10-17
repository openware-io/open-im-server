package io.openware.platform.order.api.controller;

import io.openware.platform.order.application.dto.OrderView;
import io.openware.platform.order.api.dto.ReservationView;
import io.openware.platform.order.application.OrderQueryApplicationService;
import io.openware.platform.order.application.ReservationApplicationService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consumer order history. Queries are constrained by the authenticated account's member ID.
 *
 * <p>会员归属与订单查询在 {@link OrderQueryApplicationService}；预约展示装配在
 * {@link ReservationApplicationService}。本类只做响应映射。
 */
@RestController
@RequestMapping("/me")
public class MyOrderController {
  private final OrderQueryApplicationService orderQueryService;
  private final ReservationApplicationService reservationService;

  public MyOrderController(OrderQueryApplicationService orderQueryService,
                           ReservationApplicationService reservationService) {
    this.orderQueryService = orderQueryService;
    this.reservationService = reservationService;
  }

  /** 我的订单（C 端）：按会员收窄，创建时间倒序。 */
  @GetMapping("/orders")
  public List<OrderView> orders() {
    return orderQueryService.listMine().stream().map(OrderView::from).toList();
  }

  /**
   * 我的预约（C 端）：预约按房型创建，这里回填房型名/编码与已分配包厢名，
   * 前端展示「包厢类型（房型）+ 到店后由门店分配包厢」；历史预约（只有包厢）回退显示旧包厢名。
   */
  @GetMapping("/reservations")
  public List<ReservationView> reservations() {
    return reservationService.listMine(orderQueryService.currentMemberId());
  }
}
