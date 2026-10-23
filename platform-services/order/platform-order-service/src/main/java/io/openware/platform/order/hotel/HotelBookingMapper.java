package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface HotelBookingMapper extends BaseMapper<HotelBookingPo> {

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE htl_booking SET status = 'CANCELLED', updated_at = NOW(3)
        WHERE id = #{id} AND status = 'PENDING_PAYMENT'
        """)
    int cancelPending(@Param("id") Long id);
}
