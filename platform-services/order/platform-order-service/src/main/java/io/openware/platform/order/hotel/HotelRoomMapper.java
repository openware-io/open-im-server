package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface HotelRoomMapper extends BaseMapper<HotelRoomPo> {

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE htl_room SET status = 'OCCUPIED', updated_at = NOW(3)
        WHERE id = #{id} AND tenant_id = #{tenantId} AND store_id = #{storeId} AND status = 'ACTIVE'
        """)
    int occupy(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("storeId") Long storeId);

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE htl_room SET status = 'DIRTY', updated_at = NOW(3)
        WHERE id = #{id} AND tenant_id = #{tenantId} AND store_id = #{storeId} AND status = 'OCCUPIED'
        """)
    int markDirty(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("storeId") Long storeId);
}
