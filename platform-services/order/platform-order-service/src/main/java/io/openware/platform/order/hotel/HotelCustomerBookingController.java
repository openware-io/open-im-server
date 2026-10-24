package io.openware.platform.order.hotel;

import io.openware.infrastructure.tenant.ClientAudience;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** C 端酒店报价与预订；仅 saas-c 可调用。 */
@ClientAudience("saas-c")
@RestController
@RequestMapping("/business/hotel")
public class HotelCustomerBookingController {

  private final HotelBookingApplicationService service;

  public HotelCustomerBookingController(HotelBookingApplicationService service) {
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
        request.guestName(), request.guestPhone(), request.note());
  }

  @GetMapping("/bookings")
  public List<HotelBookingPo> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return service.list(status, from, to);
  }

  public record QuoteRequest(Long roomTypeId, Long ratePlanId, LocalDate checkInDate,
                             LocalDate checkOutDate, Integer rooms) {
  }

  public record BookingRequest(Long roomTypeId, Long ratePlanId, LocalDate checkInDate,
                               LocalDate checkOutDate, Integer rooms, String guestName,
                               String guestPhone, String note) {
  }
}
