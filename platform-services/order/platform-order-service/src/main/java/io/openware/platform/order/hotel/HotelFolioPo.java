package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("htl_folio")
public class HotelFolioPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long storeId;
    private Long bookingId;
    private Long stayId;
    private String status;
    private String currency;
    private Long chargeMinor;
    private Long paidMinor;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
