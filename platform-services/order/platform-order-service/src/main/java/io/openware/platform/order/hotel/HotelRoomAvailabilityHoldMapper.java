package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface HotelRoomAvailabilityHoldMapper extends BaseMapper<HotelRoomAvailabilityHoldPo> {

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT * FROM htl_room_availability_hold -- tenant-agnostic: 到期释放按记录自身 tenant 处理
        WHERE status = 'HELD' AND expires_at < NOW(3)
        ORDER BY id LIMIT 100
        """)
    List<HotelRoomAvailabilityHoldPo> selectExpired();

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE htl_room_availability_hold SET status = 'RELEASED', updated_at = NOW(3)
        WHERE id = #{id} AND status = 'HELD'
        """)
    int releaseExpired(@Param("id") Long id);
}
