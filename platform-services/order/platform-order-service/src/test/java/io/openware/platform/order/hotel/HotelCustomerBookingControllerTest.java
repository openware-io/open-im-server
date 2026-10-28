package io.openware.platform.order.hotel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HotelCustomerBookingControllerTest {

  private final HotelBookingApplicationService service =
      org.mockito.Mockito.mock(HotelBookingApplicationService.class);
  private final HotelHousekeepingApplicationService housekeeping =
      org.mockito.Mockito.mock(HotelHousekeepingApplicationService.class);

  @AfterEach
  void clear() {
    TenantContextHolder.clear();
  }

  @Test
  void listUsesCurrentAccount() {
    TenantContextHolder.set(new TenantContext(100L, 100L, 102L, 112L, 1,
        List.of("hotel.booking.view"), null, "HOTEL", null));
    when(service.listByAccount(null, null, null, 112L)).thenReturn(List.of());

    var result = new HotelCustomerBookingController(service, housekeeping).list(null, null, null);

    assertEquals(0, result.size());
    verify(service).listByAccount(null, null, null, 112L);
  }

  @Test
  void createWritesCurrentAccountOwnership() {
    TenantContextHolder.set(new TenantContext(100L, 100L, 102L, 112L, 1,
        List.of("hotel.booking.create"), null, "HOTEL", null));
    when(service.createBooking(1L, 1L, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2),
        1, "A", "1", null, 112L, 0L)).thenReturn(new HotelBookingPo());

    new HotelCustomerBookingController(service, housekeeping).create(
        new HotelCustomerBookingController.BookingRequest(1L, 1L, LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 2), 1, "A", "1", null, 0L));

    verify(service).createBooking(1L, 1L, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2),
        1, "A", "1", null, 112L, 0L);
  }
}
