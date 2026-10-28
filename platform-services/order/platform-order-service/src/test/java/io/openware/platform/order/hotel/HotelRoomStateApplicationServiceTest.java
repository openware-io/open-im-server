package io.openware.platform.order.hotel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.domain.inventory.repository.StoreScopeRepository;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

/** 房态图三字段派生、维修调整房量、保留冲突。 */
@ExtendWith(MockitoExtension.class)
class HotelRoomStateApplicationServiceTest {

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

    @Mock private HotelRoomMapper roomMapper;
    @Mock private HotelRoomTypeMapper roomTypeMapper;
    @Mock private HotelRoomHoldMapper holdMapper;
    @Mock private HotelStayMapper stayMapper;
    @Mock private HotelBookingMapper bookingMapper;
    @Mock private HotelRoomAvailabilityMapper availabilityMapper;
    @Mock private HotelStoreScopeResolver storeScopeResolver;

    private HotelRoomStateApplicationService service;

    @BeforeEach
    void setUp() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 2L, STORE_ID, 3L, 1,
                List.of("hotel.booking.view", "hotel.room.view", "hotel.room.manage"),
                "STORE", "HOTEL", "Asia/Shanghai"));
        service = new HotelRoomStateApplicationService(roomMapper, roomTypeMapper, holdMapper,
                stayMapper, bookingMapper, availabilityMapper, storeScopeResolver);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void boardExposesThreeStatusFieldsAndActiveHold() {
        HotelRoomPo vacant = room("VACANT", "CLEAN", "NONE");
        HotelRoomPo reserved = room("VACANT", "CLEAN", "NONE");
        reserved.setId(91L);
        reserved.setRoomNo("1011");
        HotelRoomHoldPo hold = new HotelRoomHoldPo();
        hold.setId(5L);
        hold.setRoomId(91L);
        hold.setReason("GROUP_BLOCK");
        hold.setStatus("ACTIVE");
        HotelRoomTypePo type = new HotelRoomTypePo();
        type.setId(11L);
        type.setName("大床房");
        when(roomMapper.selectList(any())).thenReturn(List.of(vacant, reserved));
        when(roomTypeMapper.selectList(any())).thenReturn(List.of(type));
        when(holdMapper.selectActiveByStore(TENANT_ID, STORE_ID)).thenReturn(List.of(hold));
        when(stayMapper.selectList(any())).thenReturn(List.of());

        List<HotelRoomBoardView> board = service.board();

        assertEquals(2, board.size());
        assertEquals("VACANT", board.get(0).occupancyStatus());
        assertEquals("CLEAN", board.get(0).housekeepingStatus());
        assertEquals("NONE", board.get(0).outOfOrderStatus());
        assertEquals(null, board.get(0).holdReason());
        assertEquals("GROUP_BLOCK", board.get(1).holdReason());
        assertEquals(5L, board.get(1).holdId());
        assertEquals("大床房", board.get(1).roomTypeName());
    }

    @Test
    void outOfOrderAdjustsRoomAvailabilityForEveryDate() {
        HotelRoomPo room = room("VACANT", "CLEAN", "NONE");
        when(roomMapper.selectById(90L)).thenReturn(room);
        when(storeScopeResolver.requireHotelScope(any())).thenReturn(hotelScope());
        when(availabilityMapper.adjustTotal(any(), any(), any(), any(LocalDate.class), anyInt())).thenReturn(1);
        when(roomMapper.setOutOfOrderStatus(90L, TENANT_ID, STORE_ID, "OUT_OF_ORDER")).thenReturn(1);

        service.setOutOfOrder(90L, "OUT_OF_ORDER", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2));

        // 两天各下调 1 间房量，记录为维修占用。
        verify(availabilityMapper).adjustTotal(TENANT_ID, STORE_ID, 11L, LocalDate.of(2026, 11, 1), -1);
        verify(availabilityMapper).adjustTotal(TENANT_ID, STORE_ID, 11L, LocalDate.of(2026, 11, 2), -1);
        ArgumentCaptor<HotelRoomHoldPo> hold = ArgumentCaptor.forClass(HotelRoomHoldPo.class);
        verify(holdMapper).insert(hold.capture());
        assertEquals("MAINTENANCE", hold.getValue().getReason());
        assertEquals("ACTIVE", hold.getValue().getStatus());
        assertEquals(LocalDateTime.of(2026, 11, 3, 0, 0), hold.getValue().getEndAt());
    }

    @Test
    void restoringRoomAddsAvailabilityBack() {
        HotelRoomPo room = room("VACANT", "CLEAN", "OUT_OF_ORDER");
        HotelRoomHoldPo hold = new HotelRoomHoldPo();
        hold.setId(6L);
        hold.setRoomId(90L);
        hold.setReason("MAINTENANCE");
        hold.setStatus("ACTIVE");
        hold.setStartAt(LocalDateTime.of(2026, 11, 1, 0, 0));
        hold.setEndAt(LocalDateTime.of(2026, 11, 3, 0, 0));
        when(roomMapper.selectById(90L)).thenReturn(room);
        when(holdMapper.selectActiveByRoom(TENANT_ID, STORE_ID, 90L)).thenReturn(List.of(hold));
        when(availabilityMapper.adjustTotal(any(), any(), any(), any(LocalDate.class), anyInt())).thenReturn(1);
        when(roomMapper.clearOutOfOrderStatus(90L, TENANT_ID, STORE_ID)).thenReturn(1);

        service.setOutOfOrder(90L, "NONE", null, null);

        verify(availabilityMapper).adjustTotal(TENANT_ID, STORE_ID, 11L, LocalDate.of(2026, 11, 1), 1);
        verify(availabilityMapper).adjustTotal(TENANT_ID, STORE_ID, 11L, LocalDate.of(2026, 11, 2), 1);
        verify(roomMapper).clearOutOfOrderStatus(90L, TENANT_ID, STORE_ID);
        verify(holdMapper).updateById(hold);
        assertEquals("RELEASED", hold.getStatus());
    }

    @Test
    void occupiedRoomCannotBeSetOutOfOrder() {
        HotelRoomPo room = room("OCCUPIED", "CLEAN", "NONE");
        when(roomMapper.selectById(90L)).thenReturn(room);

        ApiException failure = assertThrows(ApiException.class,
                () -> service.setOutOfOrder(90L, "OUT_OF_ORDER", null, null));

        assertEquals("ROOM_OCCUPIED", failure.getCode());
        verify(availabilityMapper, times(0)).adjustTotal(any(), any(), any(), any(LocalDate.class), anyInt());
    }

    @Test
    void overlappingReservationIsRejected() {
        HotelRoomPo room = room("VACANT", "CLEAN", "NONE");
        HotelRoomHoldPo existing = new HotelRoomHoldPo();
        existing.setId(7L);
        existing.setRoomId(90L);
        existing.setReason("RESERVED");
        existing.setStatus("ACTIVE");
        existing.setStartAt(LocalDate.of(2026, 11, 1).atStartOfDay());
        existing.setEndAt(LocalDate.of(2026, 11, 4).atStartOfDay());
        when(roomMapper.selectById(90L)).thenReturn(room);
        when(holdMapper.selectActiveByRoom(TENANT_ID, STORE_ID, 90L)).thenReturn(List.of(existing));
        when(storeScopeResolver.requireHotelScope(any())).thenReturn(hotelScope());

        ApiException failure = assertThrows(ApiException.class, () -> service.addHold(90L, "HOUSE_USE", null,
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 2)));

        assertEquals("ROOM_HOLD_CONFLICT", failure.getCode());
    }

    private StoreScopeRepository.StoreScope hotelScope() {
        return new StoreScopeRepository.StoreScope(TENANT_ID, STORE_ID, 100L, "HOTEL", "ACTIVE");
    }

    private HotelRoomPo room(String occupancy, String housekeeping, String outOfOrder) {
        HotelRoomPo room = new HotelRoomPo();
        room.setId(90L);
        room.setTenantId(TENANT_ID);
        room.setStoreId(STORE_ID);
        room.setHotelId(3L);
        room.setRoomTypeId(11L);
        room.setRoomNo("1010");
        room.setFloor("10");
        room.setOccupancyStatus(occupancy);
        room.setHousekeepingStatus(housekeeping);
        room.setOutOfOrderStatus(outOfOrder);
        return room;
    }
}
