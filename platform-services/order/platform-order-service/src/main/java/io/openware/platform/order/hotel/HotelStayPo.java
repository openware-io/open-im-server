package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("htl_stay")
public class HotelStayPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long storeId;
    private Long bookingId;
    private Long roomId;
    private String roomNo;
    private LocalDateTime checkInAt;
    private LocalDateTime checkOutAt;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
