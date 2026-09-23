package io.openware.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import java.util.List;
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

    private OrderMapper orderMapper;
    private OrderCancellationApplicationService orderCancellationService;
    private AuditClient auditClient;
    private DailySerialNumberGenerator dailySerialNumberGenerator;
    private OrderController controller;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        orderCancellationService = mock(OrderCancellationApplicationService.class);
        auditClient = mock(AuditClient.class);
        dailySerialNumberGenerator = mock(DailySerialNumberGenerator.class);
        controller = new OrderController(orderMapper, mock(KtvSessionApplicationService.class),
                mock(KtvServerSessionApplicationService.class), mock(ResourceStateClient.class), auditClient,
                orderCancellationService, dailySerialNumberGenerator);
        TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 42L, 1,
                List.of("order.hold", "order.transfer", "order.void")));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void hold_writesSucceededAudit() {
        when(orderCancellationService.requireOrder(ORDER_ID)).thenReturn(order("SERVING"));
        when(orderMapper.updateById(any(OrderPo.class))).thenReturn(1);

        OrderPo held = controller.hold(ORDER_ID, new OrderController.HoldRequest("等客人回来"));

        assertNotNull(held.getHoldAt());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.hold", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        assertNull(record.errorCode());
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
        OrderPo serving = order("SERVING");
        serving.setHoldReason("等客人回来");
        when(orderCancellationService.requireOrder(ORDER_ID)).thenReturn(serving);
        when(orderMapper.updateById(any(OrderPo.class))).thenReturn(1);

        OrderPo released = controller.unhold(ORDER_ID);

        assertNull(released.getHoldReason());
        assertNull(released.getHoldAt());
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
        when(orderMapper.insert(any(OrderPo.class))).thenReturn(1);

        OrderPo created = controller.createOrder(null,
                new OrderController.CreateOrderRequest(null, null, null, "RETAIL", null, null));

        ArgumentCaptor<OrderPo> captor = ArgumentCaptor.forClass(OrderPo.class);
        verify(orderMapper).insert(captor.capture());
        assertEquals("O202609190001", captor.getValue().getOrderNo());
        assertNull(captor.getValue().getIdempotencyKey(), "不带 Idempotency-Key 时不得写入幂等键");
        assertEquals("O202609190001", created.getOrderNo());
    }

    private static OrderPo order(String status) {
        OrderPo po = new OrderPo();
        po.setId(ORDER_ID);
        po.setTenantId(TENANT_ID);
        po.setStoreId(STORE_ID);
        po.setOrderNo("ORD-20260918-0001");
        po.setStatus(status);
        return po;
    }

    private AuditClient.AuditRecord captured() {
        ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(captor.capture());
        return captor.getValue();
    }
}
