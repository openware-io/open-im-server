package io.openware.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.platform.order.infra.persistence.mapper.OrderItemMapper;
import io.openware.platform.order.infra.persistence.mapper.OrderMapper;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 结算（{@code /business/orders/{id}/settle}）成功与失败留痕：直接固化订单应付金额。 */
class SettlementAuditTest {

    private static final Long ORDER_ID = 88L;

    private OrderMapper orderMapper;
    private OrderAmountApplicationService orderAmounts;
    private AuditClient auditClient;
    private SettlementApplicationService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        orderAmounts = mock(OrderAmountApplicationService.class);
        auditClient = mock(AuditClient.class);
        service = new SettlementApplicationService(orderMapper, mock(OrderItemMapper.class), auditClient,
                orderAmounts);
    }

    @Test
    void settle_writesSucceededAudit() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("WAITING_SETTLEMENT", 0));
        // 结台落库走带 version 条件的乐观锁提交（受影响 1 行 = 库中版本仍是期望值）。
        when(orderMapper.settleWithVersion(any(OrderPo.class), anyInt())).thenReturn(1);

        OrderPo settled = service.settle(ORDER_ID, 0);

        assertEquals("WAITING_PAYMENT", settled.getStatus());
        assertEquals(1, settled.getVersion(), "CAS 成功后内存对象版本与库一致");
        AuditClient.AuditRecord record = captured();
        assertEquals("order.settle", record.action());
        assertEquals("结台结算", record.actionLabel());
        assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
        assertNull(record.errorCode());
    }

    /**
     * 乐观锁落库失败（受影响 0 行）必须转成冲突，**不得**当成成功：
     * 两个并发结算都读到同一 version，只靠应用层比对挡不住，真正的判定在这条 UPDATE 上。
     */
    @Test
    void settle_raisesVersionConflictWhenCasAffectsNoRow() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("WAITING_SETTLEMENT", 0));
        when(orderMapper.settleWithVersion(any(OrderPo.class), anyInt())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.settle(ORDER_ID, 0));

        assertEquals("ORDER_VERSION_CONFLICT", ex.getCode());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, captured().result());
    }

    /** CAS 必须带上调用方给的期望版本，而不是内存对象上的版本。 */
    @Test
    void settle_passesExpectedVersionToCas() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("WAITING_SETTLEMENT", 4));
        when(orderMapper.settleWithVersion(any(OrderPo.class), anyInt())).thenReturn(1);

        service.settle(ORDER_ID, 4);

        verify(orderMapper).settleWithVersion(any(OrderPo.class), eq(4));
    }

    @Test
    void settle_writesFailureAuditOnStatusConflict() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("DRAFT", 0));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.settle(ORDER_ID, 0));

        assertEquals("ORDER_STATUS_INVALID", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.settle", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("ORDER_STATUS_INVALID", record.errorCode());
        assertNull(record.idempotencyKey());
        // 失败详情不含金额。
        assertEquals(false, record.detailJson().contains("totalAmount"));
        assertEquals(String.valueOf(ORDER_ID), record.resourceId());
    }

    @Test
    void settle_writesFailureAuditOnVersionConflict() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("WAITING_SETTLEMENT", 3));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.settle(ORDER_ID, 1));

        assertEquals("ORDER_VERSION_CONFLICT", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.settle", record.action());
        assertEquals("ORDER_VERSION_CONFLICT", record.errorCode());
    }

    @Test
    void settle_writesFailureAuditWhenOrderMissing() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(null);
        when(orderMapper.selectTenantIdById(ORDER_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.settle(ORDER_ID, 0));

        assertEquals("ORDER_NOT_FOUND", ex.getCode());
        AuditClient.AuditRecord record = captured();
        assertEquals("order.settle", record.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
        assertEquals("ORDER_NOT_FOUND", record.errorCode());
    }

    /** 跨租户访问：403 TENANT_SCOPE_DENIED 同样留失败痕迹，且不触碰金额复算。 */
    @Test
    void settle_writesFailureAuditOnCrossTenantAccess() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(null);
        when(orderMapper.selectTenantIdById(ORDER_ID)).thenReturn(999L);

        ApiException ex = assertThrows(ApiException.class, () -> service.settle(ORDER_ID, 0));

        assertEquals(403, ex.getStatus());
        assertEquals("TENANT_SCOPE_DENIED", ex.getCode());
        verifyNoInteractions(orderAmounts);
        AuditClient.AuditRecord record = captured();
        assertEquals("order.settle", record.action());
        assertEquals("TENANT_SCOPE_DENIED", record.errorCode());
    }

    private static OrderPo order(String status, int version) {
        OrderPo po = new OrderPo();
        po.setId(ORDER_ID);
        po.setTenantId(1001L);
        po.setStoreId(2001L);
        po.setOrderNo("ORD-20260918-0001");
        po.setStatus(status);
        po.setVersion(version);
        po.setTotalAmount(new BigDecimal("8000"));
        po.setDiscountAmount(BigDecimal.ZERO);
        po.setTaxAmount(BigDecimal.ZERO);
        return po;
    }

    private AuditClient.AuditRecord captured() {
        ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(captor.capture());
        return captor.getValue();
    }
}
