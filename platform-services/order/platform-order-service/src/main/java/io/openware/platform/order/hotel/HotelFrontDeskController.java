package io.openware.platform.order.hotel;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import io.openware.infrastructure.tenant.ClientAudience;
import org.springframework.web.bind.annotation.RestController;

/** 酒店前台：任务、入住分房、押金、账单、退房结算。 */
@ClientAudience("saas-b")
@RestController
@RequestMapping({"/admin/hotel/frontdesk", "/business/hotel/frontdesk"})
public class HotelFrontDeskController {

    private final HotelFrontDeskApplicationService service;
    private final HotelStayApplicationService stayService;
    private final HotelBookingApplicationService bookingService;

    public HotelFrontDeskController(HotelFrontDeskApplicationService service,
                                    HotelStayApplicationService stayService,
                                    HotelBookingApplicationService bookingService) {
        this.service = service;
        this.stayService = stayService;
        this.bookingService = bookingService;
    }

    @GetMapping("/tasks")
    public List<BookingView> tasks() {
        return service.tasks().stream()
                // IN_HOUSE / DUE_OUT 必须有 stay；历史导入数据只有预订状态时不能伪装成在住客人。
                .filter(booking -> (!"IN_HOUSE".equals(booking.getStatus()) && !"DUE_OUT".equals(booking.getStatus()))
                        || service.hasStay(booking.getId()))
                .map(booking -> BookingView.from(booking, service.currentStay(booking.getId()),
                        service.currentFolio(booking.getId())))
                .toList();
    }

    @PostMapping("/bookings/{id}/checkin")
    public BookingView checkIn(@PathVariable Long id, @RequestParam(required = false) Long roomId) {
        return BookingView.from(stayService.checkIn(id, roomId), true);
    }

    @PostMapping("/bookings/{id}/confirm-deposit")
    public BookingView confirmDeposit(@PathVariable Long id) {
        return BookingView.from(bookingService.confirmDeposit(id), false);
    }

    @PostMapping("/bookings/{id}/checkout")
    public BookingView checkOut(@PathVariable Long id) {
        return BookingView.from(stayService.checkOut(id), false);
    }

    @PostMapping("/bookings/{id}/extend")
    public BookingView extend(@PathVariable Long id, @RequestBody ExtendRequest request) {
        return BookingView.from(stayService.extendStay(id, request.newCheckOutDate()), true);
    }

    @PostMapping("/bookings/{id}/move-room")
    public BookingView moveRoom(@PathVariable Long id, @RequestBody MoveRoomRequest request) {
        return BookingView.from(stayService.moveRoom(id, request.roomId()), true);
    }

    @PostMapping("/bookings/{id}/deposit")
    public HotelFolioPo collectDeposit(@PathVariable Long id, @RequestBody DepositRequest request) {
        return stayService.collectDeposit(id, request.amountMinor(), request.note());
    }

    @PostMapping("/bookings/{id}/refund")
    public HotelFolioPo refundDeposit(@PathVariable Long id, @RequestBody RefundRequest request) {
        return stayService.refundDeposit(id, request.amountMinor(), request.note(), request.method());
    }

    @PostMapping("/bookings/{id}/refund-requests")
    public HotelRefundPo requestOnlineRefund(@PathVariable Long id, @RequestBody RefundRequest request) {
        return stayService.requestOnlineRefund(id, request.amountMinor(), request.note(), 0L);
    }

    @GetMapping("/refunds")
    public List<HotelRefundPo> refunds() {
        return stayService.listOnlineRefunds();
    }

    @PostMapping("/refunds/sync")
    public List<HotelRefundPo> syncRefunds() {
        return stayService.syncOnlineRefunds();
    }

    @GetMapping("/bookings/{id}/folio")
    public HotelStayApplicationService.FolioView folio(@PathVariable Long id) {
        return stayService.folio(id);
    }

    public record DepositRequest(Long amountMinor, String note) {}

    public record RefundRequest(Long amountMinor, String note, String method) {}

    public record MoveRoomRequest(Long roomId) {}

    public record ExtendRequest(LocalDate newCheckOutDate) {}

    public record BookingView(@JsonFormat(shape = JsonFormat.Shape.STRING) Long id,
                              String bookingNo, String guestName, Long roomTypeId, String roomType, String roomNo,
                              LocalDate checkInDate, LocalDate checkOutDate, String status,
                              Long amountMinor, Long depositOrderId, Long depositAmountMinor,
                              boolean hasStay, String currency,
                              Integer nights, Integer rooms, String guestPhone, String source,
                              LocalDateTime checkInAt, LocalDateTime checkOutAt, String stayStatus,
                              Long folioChargeMinor, Long folioPaidMinor, String folioStatus) {
        static BookingView from(HotelBookingPo po, boolean hasStay) {
            return from(po, hasStay, null, null);
        }

        static BookingView from(HotelBookingPo po, HotelStayPo stay, HotelFolioPo folio) {
            return from(po, stay != null, stay, folio);
        }

        private static BookingView from(HotelBookingPo po, boolean hasStay, HotelStayPo stay, HotelFolioPo folio) {
            return new BookingView(po.getId(), po.getBookingNo(), po.getGuestName(), po.getRoomTypeId(),
                    po.getRoomType(), po.getRoomNo(), po.getCheckInDate(), po.getCheckOutDate(),
                    po.getStatus(), po.getAmountMinor(), po.getDepositOrderId(), po.getDepositAmountMinor(),
                    hasStay, po.getCurrency(), po.getNights(), po.getRooms(), po.getGuestPhone(), po.getSource(),
                    stay == null ? null : stay.getCheckInAt(), stay == null ? null : stay.getCheckOutAt(),
                    stay == null ? null : stay.getStatus(),
                    folio == null ? null : folio.getChargeMinor(), folio == null ? null : folio.getPaidMinor(),
                    folio == null ? null : folio.getStatus());
        }
    }
}
