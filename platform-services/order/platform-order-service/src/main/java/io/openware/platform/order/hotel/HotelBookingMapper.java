package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface HotelBookingMapper extends BaseMapper<HotelBookingPo> {

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE htl_booking SET status = 'CANCELLED', updated_at = NOW(3)
        WHERE id = #{id} AND status = 'PENDING_CONFIRM'
        """)
    int cancelPending(@Param("id") Long id);

    /**
     * 已支付但预订仍为 PENDING_CONFIRM 的对账集合。
     * 支付域只回写 ord_order；酒店确认由 order 域自身按订单已付事实补齐。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT b.id
          FROM htl_booking b
          JOIN ord_order o ON o.tenant_id = b.tenant_id AND o.id = b.order_id
         WHERE b.status = 'PENDING_CONFIRM'
           AND o.trade_status IN ('PAID', 'COMPLETED')
           AND o.paid_amount >= o.total_amount
         ORDER BY b.id
         LIMIT 100
        """)
    List<Long> selectPaidPendingIds();

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM htl_booking WHERE id = #{id} -- tenant-agnostic: 定时对账按记录自身 tenant 处理")
    HotelBookingPo selectByIdIgnoreTenant(@Param("id") Long id);

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT COALESCE(paid_amount, 0) FROM ord_order WHERE id = #{orderId} -- tenant-agnostic: 按订单主键读取")
    Long selectOrderPaidAmount(@Param("orderId") Long orderId);
}
