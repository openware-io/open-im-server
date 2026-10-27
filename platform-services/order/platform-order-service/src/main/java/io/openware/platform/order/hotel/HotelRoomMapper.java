package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 房态原子流转：占用/退房/清扫都通过带前置状态的条件 UPDATE 完成。 */
@Mapper
public interface HotelRoomMapper extends BaseMapper<HotelRoomPo> {

    /** 入住占用：只有空闲、清扫完成（CLEAN/INSPECTED）、非维修、且未预留的房间才能占用。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE htl_room
        SET occupancy_status = 'OCCUPIED', version = version + 1, updated_at = NOW(3)
        WHERE id = #{id} AND tenant_id = #{tenantId} AND store_id = #{storeId}
          AND occupancy_status = 'VACANT'
          AND out_of_order_status = 'NONE'
          AND housekeeping_status IN ('CLEAN', 'INSPECTED')
        """)
    int occupy(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("storeId") Long storeId);

    /** 退房腾房：占用转空闲并把清扫置为脏房；退房不改房量。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE htl_room
        SET occupancy_status = 'VACANT', housekeeping_status = 'DIRTY',
            version = version + 1, updated_at = NOW(3)
        WHERE id = #{id} AND tenant_id = #{tenantId} AND store_id = #{storeId}
        """)
    int vacateAndMarkDirty(@Param("id") Long id, @Param("tenantId") Long tenantId,
                           @Param("storeId") Long storeId);

    /** 开始退房清扫：脏房 → 清扫中。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE htl_room
        SET housekeeping_status = 'CLEANING', version = version + 1, updated_at = NOW(3)
        WHERE id = #{id} AND tenant_id = #{tenantId} AND store_id = #{storeId}
          AND occupancy_status = 'VACANT' AND housekeeping_status = 'DIRTY'
        """)
    int startCleaning(@Param("id") Long id, @Param("tenantId") Long tenantId,
                      @Param("storeId") Long storeId);

    /** 完成清扫：CLEANING（兼容历史 DIRTY）→ CLEAN。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE htl_room
        SET housekeeping_status = 'CLEAN', version = version + 1, updated_at = NOW(3)
        WHERE id = #{id} AND tenant_id = #{tenantId} AND store_id = #{storeId}
          AND housekeeping_status IN ('CLEANING', 'DIRTY')
        """)
    int finishCleaning(@Param("id") Long id, @Param("tenantId") Long tenantId,
                       @Param("storeId") Long storeId);

    /** 清扫检查：CLEAN → INSPECTED（仍可售）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE htl_room
        SET housekeeping_status = 'INSPECTED', version = version + 1, updated_at = NOW(3)
        WHERE id = #{id} AND tenant_id = #{tenantId} AND store_id = #{storeId}
          AND housekeeping_status = 'CLEAN'
        """)
    int inspect(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("storeId") Long storeId);

    /** 手工置脏：空闲房间可重新标记为脏房（在住房间不允许）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE htl_room
        SET housekeeping_status = 'DIRTY', version = version + 1, updated_at = NOW(3)
        WHERE id = #{id} AND tenant_id = #{tenantId} AND store_id = #{storeId}
          AND occupancy_status = 'VACANT'
        """)
    int markDirty(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("storeId") Long storeId);

    /** 不可售状态：只允许在空闲房间上设置。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE htl_room
        SET out_of_order_status = #{status}, version = version + 1, updated_at = NOW(3)
        WHERE id = #{id} AND tenant_id = #{tenantId} AND store_id = #{storeId}
          AND occupancy_status = 'VACANT'
        """)
    int setOutOfOrderStatus(@Param("id") Long id, @Param("tenantId") Long tenantId,
                            @Param("storeId") Long storeId, @Param("status") String status);

    /** 恢复可售：清空不可售原因。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE htl_room
        SET out_of_order_status = 'NONE', version = version + 1, updated_at = NOW(3)
        WHERE id = #{id} AND tenant_id = #{tenantId} AND store_id = #{storeId}
        """)
    int clearOutOfOrderStatus(@Param("id") Long id, @Param("tenantId") Long tenantId,
                              @Param("storeId") Long storeId);
}
