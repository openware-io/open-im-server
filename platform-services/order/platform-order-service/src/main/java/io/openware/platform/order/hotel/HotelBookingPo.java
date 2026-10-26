package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("htl_booking")
public class HotelBookingPo {
    @TableId
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;
    private Long tenantId;
    private Long storeId;
    private Long hotelId;
    private Long roomTypeId;
    private Long ratePlanId;
    private Long orderId;
    private Long depositOrderId;
    private Long depositAmountMinor;
    private Long bookedByAccountId;
    private String bookingNo;
    private String guestName;
    private String guestPhone;
    private String roomType;
    private String roomNo;
    private Integer rooms;
    private Integer nights;
    private LocalDate checkInDate;
    private LocalDate checkOutDate;
    private String status;
    private Long amountMinor;
    private String currency;
    private String source;
    private LocalDateTime holdExpiresAt;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
