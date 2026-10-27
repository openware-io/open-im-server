package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 房量占用账本：预订按夜占用房量，不分配具体房间。 */
@Data
@TableName("htl_room_availability_hold")
public class HotelRoomAvailabilityHoldPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long organizationId;
    private Long storeId;
    private Long bookingId;
    private Long roomTypeId;
    private LocalDate stayDate;
    private Integer rooms;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
