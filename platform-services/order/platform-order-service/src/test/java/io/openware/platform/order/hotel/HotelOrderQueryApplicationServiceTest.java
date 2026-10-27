package io.openware.platform.order.hotel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 酒店订单读模型：订单号/预订号/住客/房型/房号/入离/状态/金额，并沿用住客 PII 掩码。 */
@ExtendWith(MockitoExtension.class)
class HotelOrderQueryApplicationServiceTest {

    @Mock private HotelOrderQueryMapper orderQueryMapper;

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    void ordersExposeBookingAndTradeFieldsAndMaskPii() {
        TenantContextHolder.set(new TenantContext(100L, 102L, 101L, 1L, 1,
                List.of("hotel.booking.view"), "STORE", "HOTEL", "Asia/Shanghai"));
        HotelOrderView view = new HotelOrderView();
        view.setBookingId(11L);
        view.setBookingNo("HTL1");
        view.setOrderId(21L);
        view.setOrderNo("ORD1");
        view.setGuestName("张三");
        view.setRoomType("大床房");
        view.setRoomNo("1010");
        view.setCheckInDate(LocalDate.of(2026, 11, 1));
        view.setCheckOutDate(LocalDate.of(2026, 11, 3));
        view.setBookingStatus("CHECKED_IN");
        view.setTradeStatus("PAID");
        view.setAmountMinor(20_000L);
        view.setCurrency("USD");
        when(orderQueryMapper.selectHotelOrders(100L, 101L)).thenReturn(List.of(view));

        HotelOrderView row = new HotelOrderQueryApplicationService(orderQueryMapper).orders(null).getFirst();

        assertEquals("HTL1", row.getBookingNo());
        assertEquals("ORD1", row.getOrderNo());
        assertEquals("CHECKED_IN", row.getBookingStatus());
        assertEquals("PAID", row.getTradeStatus());
        assertEquals("张**", row.getGuestName());
        assertEquals(20_000L, row.getAmountMinor());
    }
}
