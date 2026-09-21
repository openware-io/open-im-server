package com.gvchat.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.exception.BusinessException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.platform.order.infra.client.PaymentCollectedClient;
import com.gvchat.platform.order.infra.persistence.mapper.OrderMapper;
import com.gvchat.platform.order.infra.persistence.po.KtvSessionPo;
import com.gvchat.platform.order.infra.persistence.po.OrderPo;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 运营取消订单（{@code POST /business/orders/{id}/cancel}）与既有作废共用实现的规则：
 * 原因必填、状态冲突 409、已收款拦截（订单快照 + payment 域流水）、包厢释放、审计内容、重复取消幂等。
 */
class OrderCancellationApplicationServiceTest {

    private static final Long ORDER_ID = 88L;
    private static final Long SESSION_ID = 555L;
    private static final Long ROOM_RESOURCE_ID = 3001L;

    private OrderMapper orderMapper;
    private KtvSessionApplicationService ktvSessionService;
    private AuditClient auditClient;
    private PaymentCollectedClient paymentCollectedClient;
    private OrderCancellationApplicationService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        ktvSessionService = mock(KtvSessionApplicationService.class);
        auditClient = mock(AuditClient.class);
        paymentCollectedClient = mock(PaymentCollectedClient.class);
        service = new OrderCancellationApplicationService(orderMapper, ktvSessionService, auditClient,
                paymentCollectedClient);
    }

    // ------------------------------------------------------------------ 原因必填

    /** 原因为空/空白：400 CANCEL_REASON_REQUIRED，且不碰订单、不取消会话、不留痕。 */
    @Test
    void cancelRequiresReason() {
        ApiException blank = assertThrows(ApiException.class, () -> service.cancel(ORDER_ID, "   "));
        assertEquals(400, blank.getStatus());
        assertEquals("CANCEL_REASON_REQUIRED", blank.getCode());
        assertEquals("取消订单必须填写原因", blank.getMessage());

        ApiException missing = assertThrows(ApiException.class, () -> service.cancel(ORDER_ID, null));
        assertEquals("CANCEL_REASON_REQUIRED", missing.getCode());

        verifyNoInteractions(orderMapper, ktvSessionService, auditClient, paymentCollectedClient);
    }

    /** 原因超长：400 CANCEL_REASON_TOO_LONG（与其它自由文本同为 255 上限）。 */
    @Test
    void cancelRejectsTooLongReason() {
        ApiException ex = assertThrows(ApiException.class, () -> service.cancel(ORDER_ID, "原".repeat(256)));

        assertEquals(400, ex.getStatus());
        assertEquals("CANCEL_REASON_TOO_LONG", ex.getCode());
        verifyNoInteractions(orderMapper);
    }

    /** 作废保持选填：不传原因仍可作废（既有后台按钮不带 reason 也不能被打断）。 */
    @Test
    void voidKeepsOptionalReason() {
        OrderPo order = order("SERVING", BigDecimal.ZERO);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);

        OrderPo result = service.voidOrder(ORDER_ID, null);

        assertEquals("VOIDED", result.getStatus());
        AuditClient.AuditRecord record = capturedAudit();
        assertEquals("order.void", record.action());
        assertEquals("订单作废", record.actionLabel());
        assertEquals("order-void:88", record.idempotencyKey());
        assertTrue(record.detailJson().contains("\"reason\":null"), record.detailJson());
    }

    // ------------------------------------------------------------------ 状态规则

    /** 已完成订单不可取消：409 ORDER_STATUS_INVALID，且失败留痕（FAILURE + 稳定 errorCode）。 */
    @Test
    void cancelRejectsCompletedOrder() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("COMPLETED", BigDecimal.ZERO));

        ApiException ex = assertThrows(ApiException.class, () -> service.cancel(ORDER_ID, "客人要求取消"));

        assertEquals(409, ex.getStatus());
        assertEquals("ORDER_STATUS_INVALID", ex.getCode());
        verify(orderMapper, never()).updateById(any(OrderPo.class));
        verify(ktvSessionService, never()).cancelByOrder(any());
        AuditClient.AuditRecord failure = capturedFailure();
        assertEquals("order.cancel", failure.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, failure.result());
        assertEquals("ORDER_STATUS_INVALID", failure.errorCode());
        // 失败留痕不带幂等键：同一动作重复失败必须各自留痕，也不得覆盖成功路径的稳定键。
        assertNull(failure.idempotencyKey());
        assertFalse(failure.detailJson().contains("100"), "失败详情不含金额: " + failure.detailJson());
    }

    /** 已退款/部分退款同样不可取消（已收资金必须走退款流程，不能直接取消）。 */
    @Test
    void cancelRejectsRefundedOrder() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("PARTIAL_REFUNDED", BigDecimal.ZERO));

        ApiException ex = assertThrows(ApiException.class, () -> service.cancel(ORDER_ID, "客人要求取消"));

        assertEquals(409, ex.getStatus());
        assertEquals("ORDER_STATUS_INVALID", ex.getCode());
    }

    /** 重复取消（订单已 VOIDED）：幂等返回既有结果，不写库、不重复释放、不重复留痕。 */
    @Test
    void cancelIsIdempotentForAlreadyCancelledOrder() {
        OrderPo order = order("VOIDED", BigDecimal.ZERO);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);

        OrderPo result = service.cancel(ORDER_ID, "客人要求取消");

        assertSame(order, result);
        assertEquals("VOIDED", result.getStatus());
        verify(orderMapper, never()).updateById(any(OrderPo.class));
        verify(ktvSessionService, never()).cancelByOrder(any());
        verifyNoInteractions(auditClient);
    }

    /** 作废保留既有行为：已作废仍 409（不幂等）。 */
    @Test
    void voidRejectsAlreadyVoidedOrder() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("VOIDED", BigDecimal.ZERO));

        ApiException ex = assertThrows(ApiException.class, () -> service.voidOrder(ORDER_ID, "作废"));

        assertEquals(409, ex.getStatus());
        assertEquals("ORDER_STATUS_INVALID", ex.getCode());
    }

    /** 订单不存在 404；跨租户 403（口径与其它订单接口一致）。 */
    @Test
    void cancelDistinguishesMissingFromCrossTenant() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(null);
        when(orderMapper.selectTenantIdById(ORDER_ID)).thenReturn(999L);
        ApiException forbidden = assertThrows(ApiException.class, () -> service.cancel(ORDER_ID, "客人要求取消"));
        assertEquals(403, forbidden.getStatus());
        assertEquals("TENANT_SCOPE_DENIED", forbidden.getCode());

        when(orderMapper.selectTenantIdById(ORDER_ID)).thenReturn(null);
        BusinessException missing = assertThrows(BusinessException.class, () -> service.cancel(ORDER_ID, "客人要求取消"));
        assertEquals("ORDER_NOT_FOUND", missing.getCode());
    }

    // ------------------------------------------------------------------ 已收款拦截

    /** 已收金额快照 > 0：409 ORDER_HAS_PAYMENT_REFUND_FIRST，不允许取消（不得静默吞掉已收金额）。 */
    @Test
    void cancelRejectsOrderWithCollectedPayment() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("SERVING", new BigDecimal("100.000000")));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.cancel(ORDER_ID, "客人要求取消"));

        assertEquals(409, ex.getStatus());
        assertEquals("ORDER_HAS_PAYMENT_REFUND_FIRST", ex.getCode());
        assertEquals("该订单已有收款，请先退款后再取消", ex.getMessage());
        verify(orderMapper, never()).updateById(any(OrderPo.class));
        verify(ktvSessionService, never()).cancelByOrder(any());
        AuditClient.AuditRecord failure = capturedFailure();
        assertEquals("order.cancel", failure.action());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, failure.result());
        assertEquals("ORDER_HAS_PAYMENT_REFUND_FIRST", failure.errorCode());
        // 已收金额是敏感信息：失败详情不得出现金额，只留订单号与状态。
        assertFalse(failure.detailJson().contains("100"), failure.detailJson());
    }

    /** 订单快照为 0 但 payment 域已有成功收款流水：同样拦截（快照回写可能滞后）。 */
    @Test
    void cancelRejectsWhenPaymentDomainHasSuccessfulCollect() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("SERVING", BigDecimal.ZERO));
        when(paymentCollectedClient.collected(ORDER_ID))
                .thenReturn(Optional.of(new PaymentCollectedClient.CollectedBreakdown(5000L, 0L, 0L)));

        ApiException ex = assertThrows(ApiException.class, () -> service.cancel(ORDER_ID, "客人要求取消"));

        assertEquals(409, ex.getStatus());
        assertEquals("ORDER_HAS_PAYMENT_REFUND_FIRST", ex.getCode());
    }

    // ------------------------------------------------------------------ 正常取消：释放包厢 + 审计

    /** 正常取消：置 VOIDED + cancelled_at、释放活动会话、审计 order.cancel 带前后状态/原因/释放的会话与包厢。 */
    @Test
    void cancelVoidsOrderReleasesSessionAndWritesAudit() {
        OrderPo order = order("SERVING", BigDecimal.ZERO);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(orderMapper.updateById(any(OrderPo.class))).thenReturn(1);
        when(ktvSessionService.cancelByOrder(ORDER_ID)).thenReturn(session(SESSION_ID, ROOM_RESOURCE_ID));

        OrderPo result = service.cancel(ORDER_ID, " 客人要求取消 ");

        assertEquals("VOIDED", result.getStatus());
        assertNotNull(result.getCancelledAt());
        verify(orderMapper).updateById(order);
        verify(ktvSessionService).cancelByOrder(ORDER_ID);

        AuditClient.AuditRecord record = capturedAudit();
        assertEquals("order.cancel", record.action());
        assertEquals("取消订单", record.actionLabel());
        assertEquals("ord_order", record.resourceType());
        assertEquals(String.valueOf(ORDER_ID), record.resourceId());
        assertEquals("order-cancel:88", record.idempotencyKey());
        assertTrue(record.detailJson().contains("\"beforeStatus\":\"SERVING\""), record.detailJson());
        assertTrue(record.detailJson().contains("\"afterStatus\":\"VOIDED\""), record.detailJson());
        assertTrue(record.detailJson().contains("\"reason\":\"客人要求取消\""),
                "原因按填写内容留痕（去掉首尾空白）: " + record.detailJson());
        assertTrue(record.detailJson().contains("\"releasedSessionId\":555"), record.detailJson());
        assertTrue(record.detailJson().contains("\"releasedResourceId\":3001"), record.detailJson());
    }

    /** 没有活动包厢会话（如纯商品订单）：照常取消，审计里释放字段为 null。 */
    @Test
    void cancelWithoutActiveSessionWritesNullReleaseFields() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("DRAFT", BigDecimal.ZERO));
        when(orderMapper.updateById(any(OrderPo.class))).thenReturn(1);
        when(ktvSessionService.cancelByOrder(ORDER_ID)).thenReturn(null);

        OrderPo result = service.cancel(ORDER_ID, "客人要求取消");

        assertEquals("VOIDED", result.getStatus());
        AuditClient.AuditRecord record = capturedAudit();
        assertTrue(record.detailJson().contains("\"releasedSessionId\":null"), record.detailJson());
        assertTrue(record.detailJson().contains("\"releasedResourceId\":null"), record.detailJson());
    }

    /** 原因里的引号不会拼出非法 JSON（审计落库失败会丢操作日志）。 */
    @Test
    void cancelEscapesReasonInAuditDetail() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("SERVING", BigDecimal.ZERO));
        when(orderMapper.updateById(any(OrderPo.class))).thenReturn(1);

        service.cancel(ORDER_ID, "客人说\"不要了\"\\改约");

        AuditClient.AuditRecord record = capturedAudit();
        assertTrue(record.detailJson().contains("客人说\\\"不要了\\\"\\\\改约"), record.detailJson());
        assertNull(record.errorCode());
    }

    // ------------------------------------------------------------------ fixtures / helpers

    private static OrderPo order(String status, BigDecimal paidAmount) {
        OrderPo po = new OrderPo();
        po.setId(ORDER_ID);
        po.setTenantId(1001L);
        po.setStoreId(2001L);
        po.setOrderNo("ORD-20260918-0001");
        po.setBusinessType("KTV");
        po.setStatus(status);
        po.setCurrencyCode("CNY");
        po.setPaidAmount(paidAmount);
        po.setVersion(0);
        return po;
    }

    private static KtvSessionPo session(Long id, Long roomResourceId) {
        KtvSessionPo po = new KtvSessionPo();
        po.setId(id);
        po.setOrderId(ORDER_ID);
        po.setRoomResourceId(roomResourceId);
        po.setStatus("CANCELLED");
        return po;
    }

    private AuditClient.AuditRecord capturedAudit() {
        ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(captor.capture());
        return captor.getValue();
    }

    /** 失败留痕：本次调用只应产生一条 FAILURE 记录（不重复留痕、不写成功痕迹）。 */
    private AuditClient.AuditRecord capturedFailure() {
        ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
        verify(auditClient).recordAsync(captor.capture());
        assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, captor.getValue().result());
        return captor.getValue();
    }
}
