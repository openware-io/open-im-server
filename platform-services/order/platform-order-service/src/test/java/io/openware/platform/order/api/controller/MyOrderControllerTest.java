package io.openware.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.OrderQueryApplicationService;
import io.openware.platform.order.application.ReservationApplicationService;
import io.openware.platform.order.application.ReservationApplicationService.CreateReservationCommand;
import io.openware.platform.order.domain.order.model.Order;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** C-side reservation create must derive tenant/store/business/customer from the signed context. */
@ExtendWith(MockitoExtension.class)
class MyOrderControllerTest {

  private static final OffsetDateTime START = OffsetDateTime.parse("2026-11-01T20:00:00+08:00");
  private static final OffsetDateTime END = START.plusHours(2);

  @Mock
  private OrderQueryApplicationService orderQueryService;

  @Mock
  private ReservationApplicationService reservationService;

  @Mock
  private io.openware.platform.order.hotel.HotelBookingStatusReader hotelBookingStatusReader;

  private MyOrderController controller;

  @BeforeEach
  void setUp() {
    controller = new MyOrderController(orderQueryService, reservationService, hotelBookingStatusReader);
    TenantContextHolder.set(new TenantContext(
        100L, 100L, 100L, 100L, 1,
        List.of("reservation.create"), "TENANT", "KTV", "Asia/Shanghai"));
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  @Test
  void myOrdersUseHotelBookingStatusForDisplayStatus() {
    Order order = new Order(49L, 100L, 100L, 102L, "O202610300011", null, "HOTEL", 10L,
        "DRAFT", "NONE", "CNY", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        new BigDecimal("1798"), BigDecimal.ZERO, BigDecimal.ZERO, null, null, 0);
    when(orderQueryService.listMine()).thenReturn(List.of(order));
    when(hotelBookingStatusReader.byOrderIds(List.of(49L))).thenReturn(Map.of(49L, "CONFIRMED"));

    var result = controller.orders();

    assertEquals(1, result.size());
    assertEquals("CONFIRMED", result.getFirst().displayStatus());
    assertEquals("DRAFT", result.getFirst().tradeStatus());
  }

  @Test
  void createReservationDerivesScopeAndCustomerFromContext() {
    when(orderQueryService.currentMemberId()).thenReturn(101L);

    controller.createReservation("idem-c-1",
        new MyOrderController.CReservationRequest(7L, START, END, 2, "C guest"));

    ArgumentCaptor<CreateReservationCommand> captor =
        ArgumentCaptor.forClass(CreateReservationCommand.class);
    verify(reservationService).create(eq(100L), eq("idem-c-1"), captor.capture());
    CreateReservationCommand command = captor.getValue();
    assertEquals("KTV", command.businessType());
    assertEquals(101L, command.customerId());
    assertEquals(100L, command.storeId());
    assertEquals(7L, command.roomTypeId());
    assertEquals(START, command.startAt());
    assertEquals(END, command.endAt());
    assertEquals(2, command.partySize());
    assertEquals("C guest", command.contact());
  }

  @Test
  void createReservationRejectsMissingRoomType() {
    ApiException error = assertThrows(ApiException.class,
        () -> controller.createReservation("idem-c-2",
            new MyOrderController.CReservationRequest(null, START, END, 2, null)));
    assertEquals(400, error.getStatus());
    assertEquals("ROOM_TYPE_REQUIRED", error.getCode());
  }

  @Test
  void createReservationRequiresReservationCreatePermission() {
    TenantContextHolder.set(new TenantContext(
        100L, 100L, 100L, 100L, 1,
        List.of("reservation.view"), "TENANT", "KTV", "Asia/Shanghai"));
    ApiException error = assertThrows(ApiException.class,
        () -> controller.createReservation("idem-c-3",
            new MyOrderController.CReservationRequest(7L, START, END, 2, null)));
    assertEquals(403, error.getStatus());
    assertEquals("PERMISSION_DENIED", error.getCode());
  }

  @Test
  void createReservationRejectsWhenContextHasNoStore() {
    TenantContextHolder.set(new TenantContext(
        100L, 100L, null, 100L, 1,
        List.of("reservation.create"), "TENANT", "KTV", "Asia/Shanghai"));
    ApiException error = assertThrows(ApiException.class,
        () -> controller.createReservation("idem-c-4",
            new MyOrderController.CReservationRequest(7L, START, END, 2, null)));
    assertEquals(401, error.getStatus());
    assertEquals("SAAS_CONTEXT_REQUIRED", error.getCode());
  }
}
