package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("htl_refund")
public class HotelRefundPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long storeId;
    private Long bookingId;
    private Long folioId;
    private Long orderId;
    private Long amountMinor;
    private String currency;
    private String method;
    private String status;
    private String externalRef;
    private String reason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
