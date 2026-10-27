package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 房量原子增减：所有变化都用条件 UPDATE，避免读改写丢更新。 */
@Mapper
public interface HotelRoomAvailabilityMapper extends BaseMapper<HotelRoomAvailabilityPo> {

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE htl_room_availability
        SET held = held + #{rooms}, version = version + 1, updated_at = NOW(3)
        WHERE tenant_id = #{tenantId} AND store_id = #{storeId}
          AND room_type_id = #{roomTypeId} AND stay_date = #{stayDate}
          AND total - sold - held >= #{rooms}
        """)
    int hold(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId,
             @Param("roomTypeId") Long roomTypeId, @Param("stayDate") LocalDate stayDate,
             @Param("rooms") int rooms);

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE htl_room_availability
        SET held = held - #{rooms}, version = version + 1, updated_at = NOW(3)
        WHERE tenant_id = #{tenantId} AND store_id = #{storeId}
          AND room_type_id = #{roomTypeId} AND stay_date = #{stayDate}
          AND held >= #{rooms}
        """)
    int releaseHeld(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId,
                    @Param("roomTypeId") Long roomTypeId, @Param("stayDate") LocalDate stayDate,
                    @Param("rooms") int rooms);

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE htl_room_availability
        SET held = held - #{rooms}, sold = sold + #{rooms}, version = version + 1, updated_at = NOW(3)
        WHERE tenant_id = #{tenantId} AND store_id = #{storeId}
          AND room_type_id = #{roomTypeId} AND stay_date = #{stayDate}
          AND held >= #{rooms}
        """)
    int confirmHeld(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId,
                    @Param("roomTypeId") Long roomTypeId, @Param("stayDate") LocalDate stayDate,
                    @Param("rooms") int rooms);

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE htl_room_availability
        SET sold = sold + #{rooms}, version = version + 1, updated_at = NOW(3)
        WHERE tenant_id = #{tenantId} AND store_id = #{storeId}
          AND room_type_id = #{roomTypeId} AND stay_date = #{stayDate}
          AND total - sold - held >= #{rooms}
        """)
    int addSold(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId,
                @Param("roomTypeId") Long roomTypeId, @Param("stayDate") LocalDate stayDate,
                @Param("rooms") int rooms);

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE htl_room_availability
        SET sold = sold - #{rooms}, version = version + 1, updated_at = NOW(3)
        WHERE tenant_id = #{tenantId} AND store_id = #{storeId}
          AND room_type_id = #{roomTypeId} AND stay_date = #{stayDate}
          AND sold >= #{rooms}
        """)
    int releaseSold(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId,
                    @Param("roomTypeId") Long roomTypeId, @Param("stayDate") LocalDate stayDate,
                    @Param("rooms") int rooms);

    /**
     * 维修/停售调整房量：物理房间不可售时把该房型的房量下调，恢复时加回。
     * 不允许把总房量调到低于已售 + 已占用。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE htl_room_availability
        SET total = total + #{delta}, version = version + 1, updated_at = NOW(3)
        WHERE tenant_id = #{tenantId} AND store_id = #{storeId}
          AND room_type_id = #{roomTypeId} AND stay_date = #{stayDate}
          AND total + #{delta} >= sold + held
        """)
    int adjustTotal(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId,
                    @Param("roomTypeId") Long roomTypeId, @Param("stayDate") LocalDate stayDate,
                    @Param("delta") int delta);
}
