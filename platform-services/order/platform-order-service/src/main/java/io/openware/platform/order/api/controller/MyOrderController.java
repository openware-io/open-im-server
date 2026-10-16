package io.openware.platform.order.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.api.dto.ReservationView;
import io.openware.platform.order.application.ReservationApplicationService;
import io.openware.platform.order.infra.persistence.mapper.CustomerLookupMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.po.OrderPo;
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
  private final ReservationApplicationService reservationService;

  public MyOrderController(CustomerLookupMapper customerLookupMapper, OrderMapper orderMapper,
                           ReservationApplicationService reservationService) {
    this.customerLookupMapper = customerLookupMapper;
    this.orderMapper = orderMapper;
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
    Long memberId = customerLookupMapper.findMemberId(context.tenantId(), context.accountId());
    if (memberId == null) {
      throw new ApiException(404, "MEMBER_NOT_FOUND", "会员档案尚未初始化");
    }
    return memberId;
  }
}
