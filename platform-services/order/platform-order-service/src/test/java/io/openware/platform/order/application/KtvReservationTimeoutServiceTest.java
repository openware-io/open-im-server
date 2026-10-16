package io.openware.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.platform.order.application.KtvReservationTimeoutService.SweepResult;
import io.openware.platform.order.domain.ktv.model.KtvSession;
import io.openware.platform.order.domain.ktv.repository.KtvSessionRepository;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.domain.reservation.repository.ReservationRepository;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.reservation.model.Reservation;
import io.openware.platform.order.domain.reservation.model.ReservationStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 预约超时（未到店）自动处理：释放包厢 / 孤儿会话收敛 / 预约未到店 / 有收款转人工。
 *
 * <p>门店实测场景：预约超时后订单没开台，会话一直 {@code RESERVED}，房态永远「已预订」占着房。
 */
class KtvReservationTimeoutServiceTest {

    private KtvSessionRepository sessionRepository;
    private ReservationRepository reservationMapper;
    private OrderRepository orderMapper;
    private KtvSessionApplicationService ktvSessionService;
    private OrderCancellationApplicationService orderCancellationService;
    private ReservationApplicationService reservationService;
    private KtvReservationTimeoutService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(KtvSessionRepository.class);
        reservationMapper = mock(ReservationRepository.class);
        orderMapper = mock(OrderRepository.class);
        ktvSessionService = mock(KtvSessionApplicationService.class);
        orderCancellationService = mock(OrderCancellationApplicationService.class);
        reservationService = mock(ReservationApplicationService.class);
        service = new KtvReservationTimeoutService(sessionRepository, reservationMapper, orderMapper,
                ktvSessionService, orderCancellationService, reservationService);
        when(reservationMapper.findNotArrivedOverdue(any())).thenReturn(List.of());
    }

    /** 超时巡检扫出的预留会话（领域模型不可变：工厂建 RESERVED，再回填主键与租户/订单）。 */
    private KtvSession reservedSession(long id, long orderId) {
        return KtvSession.reserved(100L, null, null, orderId, null, null, null).withId(id);
    }

    /** 订单夹具：领域模型不可变，用规范构造器一次成型（审计列不在模型上）。 */
    private Order order(long id, String status) {
        return new Order(id, 100L, null, 100L, "ORD-20260918-0001", null, "KTV", null, status, "CNY",
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                null, null, null, null, 0);
    }

    @Test
    void overdueReservedSession_cancelsLiveOrder_whichCascadesRoomRelease() {
        when(sessionRepository.findReservedOverdue(any())).thenReturn(List.of(reservedSession(25L, 38L)));
        when(orderMapper.findById(38L)).thenReturn(Optional.of(order(38L, "SERVING")));

        SweepResult result = service.sweep(60);

        assertEquals(1, result.releasedOrders());
        assertEquals(0, result.orphanSessions());
        verify(orderCancellationService).cancel(38L, KtvReservationTimeoutService.AUTO_RELEASE_REASON);
        // 订单取消内部级联释放占用，这里不应再重复取消会话
        verify(ktvSessionService, never()).cancelByOrder(any());
    }

    @Test
    void overdueReservedSession_withTerminalOrder_isOrphanRepairOnly() {
        when(sessionRepository.findReservedOverdue(any())).thenReturn(List.of(reservedSession(25L, 38L)));
        when(orderMapper.findById(38L)).thenReturn(Optional.of(order(38L, "VOIDED")));

        SweepResult result = service.sweep(60);

        assertEquals(1, result.orphanSessions());
        assertEquals(0, result.releasedOrders());
        verify(ktvSessionService).cancelByOrder(38L);
        verify(orderCancellationService, never()).cancel(any(), any());
    }

    @Test
    void overdueReservedSession_missingOrder_stillReleasesSession() {
        when(sessionRepository.findReservedOverdue(any())).thenReturn(List.of(reservedSession(25L, 38L)));
        when(orderMapper.findById(38L)).thenReturn(Optional.empty());

        SweepResult result = service.sweep(60);

        assertEquals(1, result.orphanSessions());
        verify(ktvSessionService).cancelByOrder(38L);
    }

    @Test
    void overdueReservedSession_withPayment_needsManualInsteadOfSilentRelease() {
        when(sessionRepository.findReservedOverdue(any())).thenReturn(List.of(reservedSession(25L, 38L)));
        when(orderMapper.findById(38L)).thenReturn(Optional.of(order(38L, "SERVING")));
        when(orderCancellationService.cancel(eq(38L), any())).thenThrow(new ApiException(409,
                OrderCancellationApplicationService.PAYMENT_REFUND_FIRST_CODE, "该订单已有收款，请先退款后再取消"));

        SweepResult result = service.sweep(60);

        assertEquals(1, result.needsManual());
        assertEquals(0, result.releasedOrders());
        // 有收款不能擅自释放包厢，也不能抛出去打断整批
        verify(ktvSessionService, never()).cancelByOrder(any());
    }

    @Test
    void overdueReservation_isMarkedNoShow() {
        when(sessionRepository.findReservedOverdue(any())).thenReturn(List.of());
        // 领域模型不可变：超时扫出的预约按规范构造器一次成型（只用到 id/tenantId）。
        Reservation po = new Reservation(36L, 100L, 100L, "R2026091800000000001", null, null, "KTV",
                null, null, null, null, null, null, ReservationStatus.PENDING, null, null, 0);
        when(reservationMapper.findNotArrivedOverdue(any())).thenReturn(List.of(po));

        SweepResult result = service.sweep(60);

        assertEquals(1, result.noShowReservations());
        verify(reservationService).noShow(36L);
    }

    @Test
    void nothingOverdue_doesNothing() {
        when(sessionRepository.findReservedOverdue(any())).thenReturn(List.of());

        SweepResult result = service.sweep(60);

        assertEquals(0, result.touched());
        assertEquals(0, result.needsManual());
        verify(ktvSessionService, never()).cancelByOrder(any());
        verify(orderCancellationService, never()).cancel(any(), any());
        verify(reservationService, never()).noShow(any());
    }

    @Test
    void oneRowFailing_doesNotStopTheBatch() {
        when(sessionRepository.findReservedOverdue(any())).thenReturn(List.of(reservedSession(1L, 11L), reservedSession(2L, 12L)));
        when(orderMapper.findById(11L)).thenThrow(new RuntimeException("boom"));
        when(orderMapper.findById(12L)).thenReturn(Optional.of(order(12L, "VOIDED")));

        SweepResult result = service.sweep(60);

        // 第一条失败被吞掉并留待下一轮，第二条照常收敛
        assertEquals(1, result.orphanSessions());
        verify(ktvSessionService).cancelByOrder(12L);
    }

    @Test
    void cutoffUsesGracePeriod() {
        when(sessionRepository.findReservedOverdue(any())).thenReturn(List.of());

        service.sweep(60);

        org.mockito.ArgumentCaptor<LocalDateTime> cutoff = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(sessionRepository).findReservedOverdue(cutoff.capture());
        long minutes = java.time.Duration.between(cutoff.getValue(), LocalDateTime.now()).toMinutes();
        assertEquals(60, minutes, 2, "宽限期应按配置生效（到店时间 + 60 分钟）");
    }
}
