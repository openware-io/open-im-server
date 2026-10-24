package io.openware.platform.order.hotel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.OrderApplicationService;
import io.openware.platform.order.domain.order.repository.MemberLookupProvider;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HotelBookingPiiTest {

  @Mock private HotelBookingMapper bookingMapper;
  @Mock private HotelRoomTypeMapper roomTypeMapper;
  @Mock private HotelRatePlanMapper ratePlanMapper;
  @Mock private HotelInventoryMapper inventoryMapper;
  @Mock private HotelInventoryHoldMapper holdMapper;
  @Mock private HotelDailyRateMapper dailyRateMapper;
  @Mock private HotelBookingNightMapper nightMapper;
  @Mock private OrderApplicationService orderApplicationService;
  @Mock private MemberLookupProvider memberLookupProvider;

  @AfterEach
  void clear() {
    TenantContextHolder.clear();
  }

  @Test
  void listMasksGuestPiiWithoutPermission() {
    TenantContextHolder.set(new TenantContext(100L, 100L, 102L, 101L, 1,
        List.of("hotel.booking.view"), "STORE", "HOTEL", "Asia/Shanghai"));
    HotelBookingPo booking = new HotelBookingPo();
    booking.setId(1L);
    booking.setGuestName("张三");
    booking.setGuestPhone("13800000014");
    booking.setBookedByAccountId(999L);
    when(bookingMapper.selectList(any())).thenReturn(List.of(booking));
    HotelBookingApplicationService service = new HotelBookingApplicationService(
        bookingMapper, roomTypeMapper, ratePlanMapper, inventoryMapper, holdMapper,
        dailyRateMapper, nightMapper, orderApplicationService, memberLookupProvider);

    HotelBookingPo result = service.list(null, null, null).getFirst();

    assertEquals("张**", result.getGuestName());
    assertEquals("138****0014", result.getGuestPhone());
  }
}
