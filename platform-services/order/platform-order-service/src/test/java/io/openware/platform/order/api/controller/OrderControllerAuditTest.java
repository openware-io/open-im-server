package io.openware.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.DailySerialNumberGenerator;
import io.openware.platform.order.application.KtvServerSessionApplicationService;
import io.openware.platform.order.application.KtvSessionApplicationService;
import io.openware.platform.order.application.OrderCancellationApplicationService;
import io.openware.platform.order.infra.client.ResourceStateClient;
import io.openware.platform.order.application.dto.OrderView;
import io.openware.platform.order.domain.ktv.model.KtvSession;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.model.RefundStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@code /business/orders/**} 挂单/解挂的成功与失败留痕：
 * 该路由直连网关、没有 BFF 兜底，两种结果都必须由领域服务自己落审计。
 */
class OrderControllerAuditTest {

    private static final Long ORDER_ID = 88L;
    private static final long TENANT_ID = 1001L;
    private static final Long STORE_ID = 2001L;

    private OrderRepository orderMapper;
    private OrderCancellationApplicationService orderCancellationService;
    private KtvSessionApplicationService ktvSessionService;
    private AuditClient auditClient;
    private DailySerialNumberGenerator dailySerialNumberGenerator;
    private OrderController controller;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderRepository.class);
        orderCancellationService = mock(OrderCancellationApplicationService.class);
        ktvSessionService = mock(KtvSessionApplicationService.class);
        auditClient = mock(AuditClient.class);
        dailySerialNumberGenerator = mock(DailySerialNumberGenerator.class);
        controller = OrderControllerTestFactory.create(orderMapper, ktvSessionService,
                mock(KtvServerSessionApplicationService.class), mock(ResourceStateClient.class), auditClient,
                orderCancellationService, dailySerialNumberGenerator);
        // 挂单是「开台中」的动作：交易状态 DRAFT + 会话 OPEN。默认给一个开台中会话，撤销并发场景各自覆盖。
        when(ktvSessionService.listByOrderIds(anyList())).thenReturn(java.util.Map.of(ORDER_ID,
                KtvSession.reserved(TENANT_ID, 1L, STORE_ID, "KTV", ORDER_ID, 3001L, null, null)
                        .withId(1L).withStatus(KtvSession.STATUS_OPEN)));
        TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 42L, 1,
                List.of("order.hold", "order.transfer", "order.void")));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void hold_writesSucceededAudit() {
        when(orderCancellationService.requireOrder(ORDER_ID)).thenReturn(order("DRAFT"));
        when(orderMapper.holdIfNotHeld(anyLong(), any(), any(), any())).thenReturn(true);

        OrderView held = controller.hold(ORDER_ID, new OrderController.HoldRequest("等客人回来"));

        assertNotNull(held.holdAt());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.hold", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        assertNull(record.errorCode());
    }

    /**
     * 挂单落库必须带「尚未挂单」条件：受影响 0 行 = 期间已被他人挂单，此时必须报错而不是当成成功。
     * 按 status 做条件挡不住这种并发（挂单不改商业状态），所以判据是 hold_at。
     */
    @Test
    void hold_raisesConflictWhenAlreadyHeldConcurrently() {
        when(orderCancellationService.requireOrder(ORDER_ID)).thenReturn(order("DRAFT"));
        when(orderMapper.holdIfNotHeld(anyLong(), any(), any(), any())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.hold(ORDER_ID, new OrderController.HoldRequest("等客人回来")));

        assertEquals("ORDER_STATUS_INVALID", ex.getCode());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, captured().result());
    }

    @Test
    void hold_writesFailureAuditWithStableErrorCode() {
        when(orderCancellationService.requireOrder(ORDER_ID)).thenReturn(order("COMPLETED"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.hold(ORDER_ID, new OrderController.HoldRequest("等客人回来")));

        assertEquals("ORDER_STATUS_INVALID", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.hold", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("ORDER_STATUS_INVALID", record.errorCode());
        // 失败留痕不带幂等键：同一动作重复失败必须各自留痕。
        assertNull(record.idempotencyKey());
        assertEquals(String.valueOf(ORDER_ID), record.resourceId());
    }

    @Test
    void unhold_writesSucceededAudit() {
        Order serving = order("DRAFT", "等客人回来");
        when(orderCancellationService.requireOrder(ORDER_ID)).thenReturn(serving);
        when(orderMapper.unholdIfHeld(anyLong(), any())).thenReturn(true);

        OrderView released = controller.unhold(ORDER_ID);

        assertNull(released.holdReason());
        assertNull(released.holdAt());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.unhold", record.action());
        assertEquals("订单解挂", record.actionLabel());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
    }

    @Test
    void unhold_writesFailureAuditWhenOrderMissing() {
        when(orderCancellationService.requireOrder(ORDER_ID))
                .thenThrow(new BusinessException("ORDER_NOT_FOUND", "订单不存在"));

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.unhold(ORDER_ID));

        assertEquals("ORDER_NOT_FOUND", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.unhold", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("ORDER_NOT_FOUND", record.errorCode());
    }

    /**
     * 快速开台建单（{@code POST /business/orders}）必须走统一单号生成器：
     * {@code O<yyyyMMdd><当日序号>}，不再是 {@code "O" + System.currentTimeMillis()}。
     */
    @Test
    void createOrder_usesDailySerialOrderNumber() {
        TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 42L, 1,
                List.of("ktv.session.open")));
        when(dailySerialNumberGenerator.next(DailySerialNumberGenerator.DocType.ORDER, TENANT_ID))
                .thenReturn("O202609190001");
        when(orderMapper.insert(any(Order.class), any())).thenAnswer(inv -> ((Order) inv.getArgument(0)).withId(7L));

        OrderView created = controller.createOrder(null,
                new OrderController.CreateOrderRequest(null, null, null, "RETAIL", null, null));

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(captor.capture(), any());
        assertEquals("O202609190001", captor.getValue().orderNo());
        assertNull(captor.getValue().idempotencyKey(), "不带 Idempotency-Key 时不得写入幂等键");
        assertEquals("O202609190001", created.orderNo());
    }

    private static Order order(String status) {
        return order(status, null);
    }

    /** 订单夹具：领域模型不可变，用规范构造器一次成型（审计列不在模型上）。 */
    private static Order order(String status, String holdReason) {
        return new Order(ORDER_ID, TENANT_ID, null, STORE_ID, "ORD-20260918-0001", null, "KTV", null, status, RefundStatus.NONE.name(), "CNY", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, holdReason, null, 0);
    }

    private AuditClient.AuditRecord captured() {
        ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(captor.capture());
        return captor.getValue();
    }
}
