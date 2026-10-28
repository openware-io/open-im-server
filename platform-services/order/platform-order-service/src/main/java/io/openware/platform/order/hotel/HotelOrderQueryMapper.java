package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 酒店订单读模型查询：htl_booking ⋈ ord_order，只读，不修改共享订单聚合。 */
@Mapper
public interface HotelOrderQueryMapper {

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT b.id AS booking_id, b.booking_no, b.order_id, o.order_no, b.organization_id, b.store_id,
               b.guest_name, b.room_type_id, b.room_type, b.room_no,
               b.check_in_date, b.check_out_date, b.status AS booking_status,
               o.trade_status, b.amount_minor, b.currency, b.created_at
          FROM htl_booking b
          LEFT JOIN ord_order o ON o.tenant_id = b.tenant_id AND o.id = b.order_id
         WHERE b.tenant_id = #{tenantId} AND b.store_id = #{storeId}
         ORDER BY b.id DESC
         LIMIT 500
        """)
    List<HotelOrderView> selectHotelOrders(@Param("tenantId") Long tenantId,
                                           @Param("storeId") Long storeId);
}
