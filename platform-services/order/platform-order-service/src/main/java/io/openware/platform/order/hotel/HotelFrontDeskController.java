package io.openware.platform.order.hotel;

import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 酒店前台动作接口；动作权限由应用服务校验。 */
@RestController
@RequestMapping({"/admin/hotel/frontdesk", "/business/hotel/frontdesk"})
public class HotelFrontDeskController {

    private final HotelFrontDeskApplicationService service;

    public HotelFrontDeskController(HotelFrontDeskApplicationService service) {
        this.service = service;
    }

    @GetMapping("/tasks")
    public List<BookingView> tasks() {
        return service.tasks().stream().map(BookingView::from).toList();
    }

    @PostMapping("/bookings/{id}/checkin")
    public BookingView checkIn(@PathVariable Long id) {
        return BookingView.from(service.checkIn(id));
    }

    @PostMapping("/bookings/{id}/checkout")
    public BookingView checkOut(@PathVariable Long id) {
        return BookingView.from(service.checkOut(id));
    }

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
