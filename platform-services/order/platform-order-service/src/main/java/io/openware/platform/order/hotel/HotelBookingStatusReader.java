package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * C 端“我的订单”状态投影读取器。
 *
 * <p>酒店订单的业务状态在 {@code htl_booking.status}，订单交易状态在
 * {@code ord_order.trade_status}；C 端用户看到的应是酒店预订状态。这里只做读投影，
 * 不做权限裁决；归属校验由 {@code /me/orders} 的会员查询完成。
 */
@Component
public class HotelBookingStatusReader {

  private final HotelBookingMapper bookingMapper;

  public HotelBookingStatusReader(HotelBookingMapper bookingMapper) {
    this.bookingMapper = bookingMapper;
  }

  public Map<Long, String> byOrderIds(Collection<Long> orderIds) {
    if (orderIds == null || orderIds.isEmpty()) return Map.of();
    List<HotelBookingPo> rows = bookingMapper.selectList(new LambdaQueryWrapper<HotelBookingPo>()
        .select(HotelBookingPo::getOrderId, HotelBookingPo::getStatus)
        .in(HotelBookingPo::getOrderId, orderIds));
    Map<Long, String> statuses = new HashMap<>();
    for (HotelBookingPo row : rows) {
      if (row.getOrderId() != null && row.getStatus() != null) {
        statuses.put(row.getOrderId(), row.getStatus());
      }
    }
    return statuses;
  }
}
