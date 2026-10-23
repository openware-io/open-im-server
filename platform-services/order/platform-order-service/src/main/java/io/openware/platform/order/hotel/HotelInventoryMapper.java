package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface HotelInventoryMapper extends BaseMapper<HotelInventoryPo> {

    @Update("""
        UPDATE htl_inventory_calendar
        SET held = held + #{rooms}, updated_at = NOW(3)
        WHERE tenant_id = #{tenantId} AND store_id = #{storeId}
          AND room_type_id = #{roomTypeId} AND stay_date = #{stayDate}
          AND total - sold - held >= #{rooms}
        """)
    int hold(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId,
             @Param("roomTypeId") Long roomTypeId, @Param("stayDate") LocalDate stayDate,
             @Param("rooms") int rooms);

    @Update("""
        UPDATE htl_inventory_calendar
        SET held = held - #{rooms}, updated_at = NOW(3)
        WHERE tenant_id = #{tenantId} AND store_id = #{storeId}
          AND room_type_id = #{roomTypeId} AND stay_date = #{stayDate}
          AND held >= #{rooms}
        """)
    int releaseHeld(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId,
                    @Param("roomTypeId") Long roomTypeId, @Param("stayDate") LocalDate stayDate,
                    @Param("rooms") int rooms);

    @Update("""
        UPDATE htl_inventory_calendar
        SET held = held - #{rooms}, sold = sold + #{rooms}, updated_at = NOW(3)
        WHERE tenant_id = #{tenantId} AND store_id = #{storeId}
          AND room_type_id = #{roomTypeId} AND stay_date = #{stayDate}
          AND held >= #{rooms}
        """)
    int confirmHeld(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId,
                    @Param("roomTypeId") Long roomTypeId, @Param("stayDate") LocalDate stayDate,
                    @Param("rooms") int rooms);

    @Update("""
        UPDATE htl_inventory_calendar
        SET sold = sold - #{rooms}, updated_at = NOW(3)
        WHERE tenant_id = #{tenantId} AND store_id = #{storeId}
          AND room_type_id = #{roomTypeId} AND stay_date = #{stayDate}
          AND sold >= #{rooms}
        """)
    int releaseSold(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId,
                    @Param("roomTypeId") Long roomTypeId, @Param("stayDate") LocalDate stayDate,
                    @Param("rooms") int rooms);
}
