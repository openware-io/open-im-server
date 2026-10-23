package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("htl_hotel")
public class HotelPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long storeId;
    private String name;
    private String address;
    private String timezone;
    private String checkInTime;
    private String checkOutTime;
    private String status;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
