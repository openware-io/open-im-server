package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("htl_booking")
public class HotelBookingPo {
    @TableId
    private Long id;
    private Long tenantId;
    private Long storeId;
    private String bookingNo;
    private String guestName;
    private String roomType;
    private String roomNo;
    private LocalDate checkInDate;
    private LocalDate checkOutDate;
    private String status;
    private Long amountMinor;
    private String currency;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
