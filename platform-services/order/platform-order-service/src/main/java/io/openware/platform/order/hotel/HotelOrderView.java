package io.openware.platform.order.hotel;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 酒店订单读模型：订单号 + 预订号 + 住客 + 房型 + 房号 + 入离 + 状态 + 金额。 */
@Data
public class HotelOrderView {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long bookingId;
    private String bookingNo;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long orderId;
    private String orderNo;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long organizationId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long storeId;
    private String guestName;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long roomTypeId;
    private String roomType;
    private String roomNo;
    private LocalDate checkInDate;
    private LocalDate checkOutDate;
    private String bookingStatus;
    private String tradeStatus;
    private Long amountMinor;
    private String currency;
    private LocalDateTime createdAt;
}
