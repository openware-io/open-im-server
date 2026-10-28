package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 房量：房型 × 日期的可售数量。房量不是商品库存，也不叫库存。 */
@Data
@TableName("htl_room_availability")
public class HotelRoomAvailabilityPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long organizationId;
    private Long storeId;
    private Long roomTypeId;
    private LocalDate stayDate;
    private Integer total;
    private Integer sold;
    private Integer held;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
