package io.openware.platform.order.hotel;

import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 酒店前台：任务、入住分房、押金、账单、退房结算。 */
@RestController
@RequestMapping({"/admin/hotel/frontdesk", "/business/hotel/frontdesk"})
public class HotelFrontDeskController {

    private final HotelFrontDeskApplicationService service;
    private final HotelStayApplicationService stayService;

    public HotelFrontDeskController(HotelFrontDeskApplicationService service,
                                    HotelStayApplicationService stayService) {
        this.service = service;
        this.stayService = stayService;
    }

    @GetMapping("/tasks")
    public List<BookingView> tasks() {
        return service.tasks().stream().map(BookingView::from).toList();
    }

    @PostMapping("/bookings/{id}/checkin")
    public BookingView checkIn(@PathVariable Long id) {
        return BookingView.from(stayService.checkIn(id));
    }

    @PostMapping("/bookings/{id}/checkout")
    public BookingView checkOut(@PathVariable Long id) {
        return BookingView.from(stayService.checkOut(id));
    }

    @PostMapping("/bookings/{id}/move-room")
    public BookingView moveRoom(@PathVariable Long id, @RequestBody MoveRoomRequest request) {
        return BookingView.from(stayService.moveRoom(id, request.roomId()));
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

    public record BookingView(Long id, String bookingNo, String guestName, String roomType, String roomNo,
                              LocalDate checkInDate, LocalDate checkOutDate, String status,
                              Long amountMinor, String currency) {
        static BookingView from(HotelBookingPo po) {
            return new BookingView(po.getId(), po.getBookingNo(), po.getGuestName(), po.getRoomType(),
                    po.getRoomNo(), po.getCheckInDate(), po.getCheckOutDate(), po.getStatus(),
                    po.getAmountMinor(), po.getCurrency());
        }
    }
}
