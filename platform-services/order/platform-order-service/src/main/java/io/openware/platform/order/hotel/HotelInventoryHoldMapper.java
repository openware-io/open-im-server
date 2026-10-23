package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface HotelInventoryHoldMapper extends BaseMapper<HotelInventoryHoldPo> {

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT * FROM htl_inventory_hold
        WHERE status = 'HELD' AND expires_at < NOW(3)
        ORDER BY id LIMIT 100
        """)
    List<HotelInventoryHoldPo> selectExpired();

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE htl_inventory_hold SET status = 'RELEASED', updated_at = NOW(3)
        WHERE id = #{id} AND status = 'HELD'
        """)
    int releaseExpired(@Param("id") Long id);
}
