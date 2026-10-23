package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface HotelBookingNightMapper extends BaseMapper<HotelBookingNightPo> {

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE htl_booking_night SET status = 'CANCELLED', updated_at = NOW(3)
        WHERE booking_id = #{bookingId} AND status = 'HELD'
        """)
    int cancelHeldNights(@Param("bookingId") Long bookingId);
}
