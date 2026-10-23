package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("htl_daily_rate")
public class HotelDailyRatePo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long storeId;
    private Long roomTypeId;
    private Long ratePlanId;
    private LocalDate stayDate;
    private Long priceMinor;
    private String currency;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
