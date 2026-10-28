package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 客房保留/占用：RESERVED/GROUP_BLOCK/HOUSE_USE/MAINTENANCE/OUT_OF_SERVICE。 */
@Data
@TableName("htl_room_hold")
public class HotelRoomHoldPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long organizationId;
    private Long storeId;
    private Long roomId;
    private Long bookingId;
    private String reason;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
