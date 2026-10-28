package io.openware.platform.order.hotel;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;

/**
 * 前台房态图一行：只暴露三组房态字段，展示态由前端按三组字段派生。
 * 不再返回单一 room.status，避免业务继续按旧状态推断。
 */
public record HotelRoomBoardView(@JsonFormat(shape = JsonFormat.Shape.STRING) Long roomId,
                                 String roomNo, String floor,
                                 @JsonFormat(shape = JsonFormat.Shape.STRING) Long roomTypeId,
                                 String roomTypeName,
                                 String occupancyStatus, String housekeepingStatus, String outOfOrderStatus,
                                 String holdReason,
                                 @JsonFormat(shape = JsonFormat.Shape.STRING) Long holdId,
                                 @JsonFormat(shape = JsonFormat.Shape.STRING) Long bookingId,
                                 String bookingNo, String guestName,
                                 LocalDate checkInDate, LocalDate checkOutDate) {
}
