package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

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
    private String status;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
