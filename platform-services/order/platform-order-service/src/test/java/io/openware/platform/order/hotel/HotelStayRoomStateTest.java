package io.openware.platform.order.hotel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 入住/退房只动房态，不重复扣房量；脏房/维修房/预留房不可入住。 */
@ExtendWith(MockitoExtension.class)
class HotelStayRoomStateTest {

    private static final long TENANT_ID = 1L;
    private static final long STORE_ID = 7L;

    /** LambdaQueryWrapper 列名解析依赖 MyBatis-Plus TableInfo 缓存，必须在纯单测里显式初始化。 */
    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : List.of(HotelBookingPo.class, HotelRoomPo.class, HotelRoomTypePo.class,
                HotelStayPo.class, HotelRoomHoldPo.class, HotelRoomAvailabilityPo.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @Mock private HotelBookingMapper bookingMapper;
    @Mock private HotelRoomMapper roomMapper;
    @Mock private HotelStayMapper stayMapper;
    @Mock private HotelFolioMapper folioMapper;
    @Mock private HotelFolioItemMapper folioItemMapper;
    @Mock private HotelHousekeepingMapper housekeepingMapper;
    @Mock private HotelRefundMapper refundMapper;
    @Mock private HotelPaymentRefundClient paymentRefundClient;
    @Mock private HotelRoomAvailabilityMapper availabilityMapper;
    @Mock private HotelRoomHoldMapper roomHoldMapper;
    @Mock private HotelRatePlanMapper ratePlanMapper;
    @Mock private HotelDailyRateMapper dailyRateMapper;
    @Mock private HotelBookingNightMapper nightMapper;

    private HotelStayApplicationService service;

    @BeforeEach
    void setUp() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 3L, 1,
                List.of("hotel.checkin", "hotel.checkout", "hotel.folio.view", "hotel.folio.manage"),
                "STORE", "HOTEL", "Asia/Shanghai"));
        service = new HotelStayApplicationService(bookingMapper, roomMapper, stayMapper, folioMapper,
                folioItemMapper, housekeepingMapper, refundMapper, paymentRefundClient,
                availabilityMapper, roomHoldMapper, ratePlanMapper, dailyRateMapper, nightMapper);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void checkInAssignsVacantCleanRoomWithoutTouchingAvailability() {
        HotelBookingPo booking = booking("CONFIRMED");
        HotelRoomPo room = room("VACANT", "CLEAN", "NONE");
        when(bookingMapper.selectById(501L)).thenReturn(booking);
        when(stayMapper.selectOne(any())).thenReturn(null);
        when(roomHoldMapper.selectActiveByStore(TENANT_ID, STORE_ID)).thenReturn(List.of());
        when(roomMapper.selectList(any())).thenReturn(List.of(room));
        when(roomMapper.occupy(90L, TENANT_ID, STORE_ID)).thenReturn(1);

        HotelBookingPo checkedIn = service.checkIn(501L, null);

        assertEquals("CHECKED_IN", checkedIn.getStatus());
        assertEquals("1010", checkedIn.getRoomNo());
        verify(roomMapper).occupy(90L, TENANT_ID, STORE_ID);
        verify(stayMapper).insert(any(HotelStayPo.class));
        // 入住不二次扣房量：房量在预订确认时已经 SOLD。
        verifyNoInteractions(availabilityMapper);
    }

    @Test
    void dirtyRoomCannotBeCheckedIn() {
        HotelBookingPo booking = booking("CONFIRMED");
        HotelRoomPo dirty = room("VACANT", "DIRTY", "NONE");
        when(bookingMapper.selectById(501L)).thenReturn(booking);
        when(stayMapper.selectOne(any())).thenReturn(null);
        when(roomHoldMapper.selectActiveByStore(TENANT_ID, STORE_ID)).thenReturn(List.of());
        when(roomMapper.selectOne(any())).thenReturn(dirty);

        ApiException failure = assertThrows(ApiException.class, () -> service.checkIn(501L, 90L));

        assertEquals("ROOM_UNAVAILABLE", failure.getCode());
        verify(roomMapper, never()).occupy(any(), any(), any());
        verifyNoInteractions(availabilityMapper);
    }

    @Test
    void reservedRoomCannotBeCheckedIn() {
        HotelBookingPo booking = booking("CONFIRMED");
        HotelRoomPo room = room("VACANT", "CLEAN", "NONE");
        HotelRoomHoldPo hold = new HotelRoomHoldPo();
        hold.setId(5L);
        hold.setRoomId(90L);
        hold.setReason("RESERVED");
        hold.setStatus("ACTIVE");
        when(bookingMapper.selectById(501L)).thenReturn(booking);
        when(stayMapper.selectOne(any())).thenReturn(null);
        when(roomHoldMapper.selectActiveByStore(TENANT_ID, STORE_ID)).thenReturn(List.of(hold));
        when(roomMapper.selectOne(any())).thenReturn(room);

        ApiException failure = assertThrows(ApiException.class, () -> service.checkIn(501L, 90L));

        assertEquals("ROOM_UNAVAILABLE", failure.getCode());
        verify(roomMapper, never()).occupy(any(), any(), any());
        verifyNoInteractions(availabilityMapper);
    }

    @Test
    void checkOutTurnsRoomDirtyAndCreatesDepartureTaskWithoutTouchingAvailability() {
        HotelBookingPo booking = booking("CHECKED_IN");
        booking.setOrderId(700L);
        HotelStayPo stay = new HotelStayPo();
        stay.setId(60L);
        stay.setBookingId(501L);
        stay.setRoomId(90L);
        stay.setRoomNo("1010");
        stay.setStatus("IN_HOUSE");
        HotelFolioPo folio = new HotelFolioPo();
        folio.setId(80L);
        folio.setStoreId(STORE_ID);
        folio.setBookingId(501L);
        folio.setStatus("OPEN");
        folio.setCurrency("USD");
        folio.setChargeMinor(10_000L);
        folio.setPaidMinor(10_000L);
        when(bookingMapper.selectById(501L)).thenReturn(booking);
        when(stayMapper.selectOne(any())).thenReturn(stay);
        when(folioMapper.selectOne(any())).thenReturn(folio);

        HotelBookingPo checkedOut = service.checkOut(501L);

        assertEquals("CHECKED_OUT", checkedOut.getStatus());
        verify(roomMapper).vacateAndMarkDirty(90L, TENANT_ID, STORE_ID);
        ArgumentCaptor<HotelHousekeepingPo> task = ArgumentCaptor.forClass(HotelHousekeepingPo.class);
        verify(housekeepingMapper).insert(task.capture());
        assertEquals(HotelHousekeepingApplicationService.TYPE_DEPARTURE, task.getValue().getTaskType());
        assertEquals("CHECKOUT", task.getValue().getSource());
        assertEquals(90L, task.getValue().getRoomId());
        // 退房不改房量。
        verifyNoInteractions(availabilityMapper);
    }

    private HotelBookingPo booking(String status) {
        HotelBookingPo booking = new HotelBookingPo();
        booking.setId(501L);
        booking.setTenantId(TENANT_ID);
        booking.setStoreId(STORE_ID);
        booking.setRoomTypeId(11L);
        booking.setRoomNo(null);
        booking.setStatus(status);
        booking.setAmountMinor(10_000L);
        booking.setCurrency("USD");
        booking.setCheckInDate(LocalDate.now());
        booking.setCheckOutDate(LocalDate.now().plusDays(1));
        return booking;
    }

    private HotelRoomPo room(String occupancy, String housekeeping, String outOfOrder) {
        HotelRoomPo room = new HotelRoomPo();
        room.setId(90L);
        room.setTenantId(TENANT_ID);
        room.setStoreId(STORE_ID);
        room.setRoomTypeId(11L);
        room.setRoomNo("1010");
        room.setOccupancyStatus(occupancy);
        room.setHousekeepingStatus(housekeeping);
        room.setOutOfOrderStatus(outOfOrder);
        return room;
    }
}
