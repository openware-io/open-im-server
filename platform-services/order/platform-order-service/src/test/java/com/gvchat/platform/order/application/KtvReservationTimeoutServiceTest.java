package com.gvchat.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.exception.ApiException;
import com.gvchat.platform.order.application.KtvReservationTimeoutService.SweepResult;
import com.gvchat.platform.order.infra.persistence.mapper.KtvSessionMapper;
import com.gvchat.platform.order.infra.persistence.mapper.OrderMapper;
import com.gvchat.platform.order.infra.persistence.mapper.ReservationMapper;
import com.gvchat.platform.order.infra.persistence.po.KtvSessionPo;
import com.gvchat.platform.order.infra.persistence.po.OrderPo;
import com.gvchat.platform.order.infra.persistence.po.ReservationPo;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 预约超时（未到店）自动处理：释放包厢 / 孤儿会话收敛 / 预约未到店 / 有收款转人工。
 *
 * <p>门店实测场景：预约超时后订单没开台，会话一直 {@code RESERVED}，房态永远「已预订」占着房。
 */
class KtvReservationTimeoutServiceTest {

    private KtvSessionMapper sessionMapper;
    private ReservationMapper reservationMapper;
    private OrderMapper orderMapper;
    private KtvSessionApplicationService ktvSessionService;
    private OrderCancellationApplicationService orderCancellationService;
    private ReservationApplicationService reservationService;
    private KtvReservationTimeoutService service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(KtvSessionMapper.class);
        reservationMapper = mock(ReservationMapper.class);
        orderMapper = mock(OrderMapper.class);
        ktvSessionService = mock(KtvSessionApplicationService.class);
        orderCancellationService = mock(OrderCancellationApplicationService.class);
        reservationService = mock(ReservationApplicationService.class);
        service = new KtvReservationTimeoutService(sessionMapper, reservationMapper, orderMapper,
                ktvSessionService, orderCancellationService, reservationService);
        when(reservationMapper.selectNotArrivedOverdue(any())).thenReturn(List.of());
    }

    private KtvSessionPo reservedSession(long id, long orderId) {
        KtvSessionPo po = new KtvSessionPo();
        po.setId(id);
        po.setTenantId(100L);
        po.setOrderId(orderId);
        po.setStatus("RESERVED");
        return po;
    }

    private OrderPo order(long id, String status) {
        OrderPo po = new OrderPo();
        po.setId(id);
        po.setStatus(status);
        return po;
    }

    @Test
    void overdueReservedSession_cancelsLiveOrder_whichCascadesRoomRelease() {
        when(sessionMapper.selectReservedOverdue(any())).thenReturn(List.of(reservedSession(25L, 38L)));
        when(orderMapper.selectById(38L)).thenReturn(order(38L, "SERVING"));

        SweepResult result = service.sweep(60);

        assertEquals(1, result.releasedOrders());
        assertEquals(0, result.orphanSessions());
        verify(orderCancellationService).cancel(38L, KtvReservationTimeoutService.AUTO_RELEASE_REASON);
        // 订单取消内部级联释放占用，这里不应再重复取消会话
        verify(ktvSessionService, never()).cancelByOrder(any());
    }

    @Test
    void overdueReservedSession_withTerminalOrder_isOrphanRepairOnly() {
        when(sessionMapper.selectReservedOverdue(any())).thenReturn(List.of(reservedSession(25L, 38L)));
        when(orderMapper.selectById(38L)).thenReturn(order(38L, "VOIDED"));

        SweepResult result = service.sweep(60);

        assertEquals(1, result.orphanSessions());
        assertEquals(0, result.releasedOrders());
        verify(ktvSessionService).cancelByOrder(38L);
        verify(orderCancellationService, never()).cancel(any(), any());
    }

    @Test
    void overdueReservedSession_missingOrder_stillReleasesSession() {
        when(sessionMapper.selectReservedOverdue(any())).thenReturn(List.of(reservedSession(25L, 38L)));
        when(orderMapper.selectById(38L)).thenReturn(null);

        SweepResult result = service.sweep(60);

        assertEquals(1, result.orphanSessions());
        verify(ktvSessionService).cancelByOrder(38L);
    }

    @Test
    void overdueReservedSession_withPayment_needsManualInsteadOfSilentRelease() {
        when(sessionMapper.selectReservedOverdue(any())).thenReturn(List.of(reservedSession(25L, 38L)));
        when(orderMapper.selectById(38L)).thenReturn(order(38L, "SERVING"));
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
        when(sessionMapper.selectReservedOverdue(any())).thenReturn(List.of());
        ReservationPo po = new ReservationPo();
        po.setId(36L);
        po.setTenantId(100L);
        when(reservationMapper.selectNotArrivedOverdue(any())).thenReturn(List.of(po));

        SweepResult result = service.sweep(60);

        assertEquals(1, result.noShowReservations());
        verify(reservationService).noShow(36L);
    }

    @Test
    void nothingOverdue_doesNothing() {
        when(sessionMapper.selectReservedOverdue(any())).thenReturn(List.of());

        SweepResult result = service.sweep(60);

        assertEquals(0, result.touched());
        assertEquals(0, result.needsManual());
        verify(ktvSessionService, never()).cancelByOrder(any());
        verify(orderCancellationService, never()).cancel(any(), any());
        verify(reservationService, never()).noShow(any());
    }

    @Test
    void oneRowFailing_doesNotStopTheBatch() {
        when(sessionMapper.selectReservedOverdue(any())).thenReturn(List.of(reservedSession(1L, 11L), reservedSession(2L, 12L)));
        when(orderMapper.selectById(11L)).thenThrow(new RuntimeException("boom"));
        when(orderMapper.selectById(12L)).thenReturn(order(12L, "VOIDED"));

        SweepResult result = service.sweep(60);

        // 第一条失败被吞掉并留待下一轮，第二条照常收敛
        assertEquals(1, result.orphanSessions());
        verify(ktvSessionService).cancelByOrder(12L);
    }

    @Test
    void cutoffUsesGracePeriod() {
        when(sessionMapper.selectReservedOverdue(any())).thenReturn(List.of());

        service.sweep(60);

        org.mockito.ArgumentCaptor<LocalDateTime> cutoff = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(sessionMapper).selectReservedOverdue(cutoff.capture());
        long minutes = java.time.Duration.between(cutoff.getValue(), LocalDateTime.now()).toMinutes();
        assertEquals(60, minutes, 2, "宽限期应按配置生效（到店时间 + 60 分钟）");
    }
}
