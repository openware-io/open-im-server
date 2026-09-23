package io.openware.platform.order.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.ReservationApplicationService;
import io.openware.platform.order.infra.persistence.mapper.CustomerLookupMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.mapper.ReservationMapper;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import io.openware.platform.order.infra.persistence.po.ReservationPo;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Consumer order history. Queries are constrained by the authenticated account's member ID. */
@RestController
@RequestMapping("/me")
public class MyOrderController {
  private final CustomerLookupMapper customerLookupMapper;
  private final OrderMapper orderMapper;
  private final ReservationMapper reservationMapper;
  private final ReservationApplicationService reservationService;

  public MyOrderController(CustomerLookupMapper customerLookupMapper, OrderMapper orderMapper,
                           ReservationMapper reservationMapper, ReservationApplicationService reservationService) {
    this.customerLookupMapper = customerLookupMapper;
    this.orderMapper = orderMapper;
    this.reservationMapper = reservationMapper;
    this.reservationService = reservationService;
  }

  @GetMapping("/orders")
  public List<OrderPo> orders() {
    return orderMapper.selectList(new LambdaQueryWrapper<OrderPo>().eq(OrderPo::getCustomerId, memberId())
        .orderByDesc(OrderPo::getCreatedAt));
  }

  /**
   * 我的预约（C 端）：预约按房型创建，这里回填房型名/编码与已分配包厢名，
   * 前端展示「包厢类型（房型）+ 到店后由门店分配包厢」；历史预约（只有包厢）回退显示旧包厢名。
   */
  @GetMapping("/reservations")
  public List<ReservationPo> reservations() {
    return reservationService.enrichForDisplay(reservationMapper.selectList(
        new LambdaQueryWrapper<ReservationPo>().eq(ReservationPo::getCustomerId, memberId())
            .orderByDesc(ReservationPo::getStartAt)));
  }

  private long memberId() {
    TenantContext context = TenantContextHolder.get();
    if (context == null) {
      throw new ApiException(401, "ACCOUNT_REQUIRED", "缺少已认证账号");
    }
    Long memberId = customerLookupMapper.findMemberId(context.tenantId(), context.accountId());
    if (memberId == null) {
      throw new ApiException(404, "MEMBER_NOT_FOUND", "会员档案尚未初始化");
    }
    return memberId;
  }
}
