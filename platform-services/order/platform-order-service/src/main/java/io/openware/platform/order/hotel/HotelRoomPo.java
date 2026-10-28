package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 物理房间。房态由三组字段表达，不再使用单一 status：
 * occupancy_status / housekeeping_status / out_of_order_status。
 */
@Data
@TableName("htl_room")
public class HotelRoomPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long storeId;
    private Long hotelId;
    private Long roomTypeId;
    private String roomNo;
    private String floor;
    private String occupancyStatus;
    private String housekeepingStatus;
    private String outOfOrderStatus;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
