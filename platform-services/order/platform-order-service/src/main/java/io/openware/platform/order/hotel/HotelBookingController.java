package io.openware.platform.order.hotel;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import io.openware.infrastructure.tenant.ClientAudience;
import org.springframework.web.bind.annotation.RestController;

/** 酒店报价、预订、确认与取消。 */
@ClientAudience("saas-b")
@RestController
@RequestMapping("/admin/hotel")
public class HotelBookingController {

    private final HotelBookingApplicationService service;

    public HotelBookingController(HotelBookingApplicationService service) {
        this.service = service;
    }

    @PostMapping("/quotes")
    public HotelBookingApplicationService.Quote quote(@RequestBody QuoteRequest request) {
        return service.quote(request.roomTypeId(), request.ratePlanId(), request.checkInDate(),
                request.checkOutDate(), request.rooms() == null ? 1 : request.rooms());
    }

    @PostMapping("/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public HotelBookingPo create(@RequestBody BookingRequest request) {
        return service.createBooking(request.roomTypeId(), request.ratePlanId(), request.checkInDate(),
                request.checkOutDate(), request.rooms() == null ? 1 : request.rooms(),
                request.guestName(), request.guestPhone(), request.note(), request.bookedByAccountId());
    }

    @GetMapping("/bookings")
    public List<HotelBookingPo> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.list(status, from, to);
    }

    @PostMapping("/bookings/{id}/confirm")
    public HotelBookingPo confirm(@PathVariable Long id) {
        return service.confirm(id);
    }

    @PostMapping("/bookings/{id}/cancel")
    public HotelBookingPo cancel(@PathVariable Long id) {
        return service.cancel(id);
    }

    @PostMapping("/bookings/{id}/no-show")
    public HotelBookingPo noShow(@PathVariable Long id) {
        return service.noShow(id);
    }

    public record QuoteRequest(Long roomTypeId, Long ratePlanId, LocalDate checkInDate, LocalDate checkOutDate,
                               Integer rooms) {}

    public record BookingRequest(Long roomTypeId, Long ratePlanId, LocalDate checkInDate, LocalDate checkOutDate,
                                 Integer rooms, String guestName, String guestPhone, String note,
                                 Long bookedByAccountId) {}
}
