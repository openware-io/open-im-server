package io.openware.platform.order.hotel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.domain.order.repository.MemberLookupProvider;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HotelBookingControllerTest {

  private HotelBookingApplicationService service;
  private HotelOrderQueryApplicationService orderQuery;
  private IdentityAccountLookupClient identityLookup;
  private MemberLookupProvider memberLookup;
  private HotelBookingController controller;

  @BeforeEach
  void setUp() {
    service = mock(HotelBookingApplicationService.class);
    orderQuery = mock(HotelOrderQueryApplicationService.class);
    identityLookup = mock(IdentityAccountLookupClient.class);
    memberLookup = mock(MemberLookupProvider.class);
    controller = new HotelBookingController(service, orderQuery, identityLookup, memberLookup);
    TenantContextHolder.set(new TenantContext(100L, 100L, 102L, 1L, 1,
        List.of("hotel.booking.view", "hotel.booking.create"), null, "HOTEL", null));
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  @Test
  void listPassesBoundedPagination() {
    controller.list(null, null, null, 20, 40);
    verify(service).list(null, null, null, 20, 40);
  }

  @Test
  void confirmPassesExpectedVersion() {
    controller.confirm(9L, 3);
    verify(service).confirm(9L, 3);
  }

  @Test
  void phoneAttributionOnlyUsedWhenCustomerMemberExists() {
    when(identityLookup.findByPhone("13800138000")).thenReturn(Optional.of(120L));
    when(memberLookup.findMemberId(100L, 120L)).thenReturn(Optional.empty());
    HotelBookingController.BookingRequest request = new HotelBookingController.BookingRequest(
        1L, 1L, LocalDate.now().plusDays(1), LocalDate.now().plusDays(2), 1,
        "guest", "13800138000", null, null, 0L);

    controller.create(request);

    verify(service).createBooking(eq(1L), eq(1L), any(), any(), eq(1), eq("guest"),
        eq("13800138000"), isNull(), isNull(), eq(0L));
  }

  @Test
  void phoneAttributionUsedWhenCustomerMemberExists() {
    when(identityLookup.findByPhone("13800138000")).thenReturn(Optional.of(120L));
    when(memberLookup.findMemberId(100L, 120L)).thenReturn(Optional.of(10L));
    HotelBookingController.BookingRequest request = new HotelBookingController.BookingRequest(
        1L, 1L, LocalDate.now().plusDays(1), LocalDate.now().plusDays(2), 1,
        "guest", "13800138000", null, null, 0L);

    controller.create(request);

    verify(service).createBooking(eq(1L), eq(1L), any(), any(), eq(1), eq("guest"),
        eq("13800138000"), isNull(), eq(120L), eq(0L));
  }
}
