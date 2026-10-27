package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HotelRoomHoldMapper extends BaseMapper<HotelRoomHoldPo> {

    /** 门店内有效保留/不可售记录；租户/门店条件显式写在 SQL 里。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT * FROM htl_room_hold
        WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND status = 'ACTIVE'
        ORDER BY id
        """)
    List<HotelRoomHoldPo> selectActiveByStore(@Param("tenantId") Long tenantId,
                                              @Param("storeId") Long storeId);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT * FROM htl_room_hold
        WHERE tenant_id = #{tenantId} AND store_id = #{storeId}
          AND room_id = #{roomId} AND status = 'ACTIVE'
        ORDER BY id
        """)
    List<HotelRoomHoldPo> selectActiveByRoom(@Param("tenantId") Long tenantId,
                                             @Param("storeId") Long storeId,
                                             @Param("roomId") Long roomId);
}
