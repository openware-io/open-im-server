package io.openware.common.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.payment.infra.persistence.mapper.OrderBillingMapper;
import io.openware.common.payment.infra.persistence.mapper.RefundMapper;
import io.openware.common.payment.infra.persistence.po.RefundPo;
import io.openware.infrastructure.audit.AuditClient;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 退款审批状态机：PENDING → APPROVED/REJECTED → REFUNDED，含非法转换拒绝。 */
class RefundApplicationServiceTest {

  private final RefundMapper refundMapper = mock(RefundMapper.class);
  private final AuditClient auditClient = mock(AuditClient.class);
  private final RefundApplicationService service = new RefundApplicationService(refundMapper, auditClient);

  @Test
  void requestRefund_createsPendingRefund() {
    RefundDto result = service.requestRefund(1L, 9L, new BigDecimal("50.00"), "多收退款", 7L);

    assertEquals(RefundApplicationService.STATUS_PENDING, result.status());
    // 退款币种快照：订单读不到（未注入账单查询）时回退当时租户币种，缺省 USD（§1/§5）。
    assertEquals("USD", result.currencyCode());
    ArgumentCaptor<RefundPo> captor = ArgumentCaptor.forClass(RefundPo.class);
    verify(refundMapper).insert(captor.capture());
    assertEquals(RefundApplicationService.STATUS_PENDING, captor.getValue().getStatus());
    assertEquals(0, new BigDecimal("50.00").compareTo(captor.getValue().getRequestedAmount()));
  }

  @Test
  void approveRefund_movesPendingToApproved() {
    RefundPo pending = refund(1L, RefundApplicationService.STATUS_PENDING, new BigDecimal("50.00"));
    when(refundMapper.selectById(1L)).thenReturn(pending);

    RefundDto result = service.approveRefund(1L, new BigDecimal("50.00"), 8L);

    assertEquals(RefundApplicationService.STATUS_APPROVED, result.status());
    assertEquals(0, new BigDecimal("50.00").compareTo(result.approvedAmount()));
    assertEquals(8L, result.approvedBy());
    verify(refundMapper).updateById(pending);
  }

  @Test
  void rejectRefund_movesPendingToRejected() {
    RefundPo pending = refund(1L, RefundApplicationService.STATUS_PENDING, new BigDecimal("50.00"));
    when(refundMapper.selectById(1L)).thenReturn(pending);

    RefundDto result = service.rejectRefund(1L, 8L);

    assertEquals(RefundApplicationService.STATUS_REJECTED, result.status());
    assertEquals(8L, result.approvedBy());
    verify(refundMapper).updateById(pending);
  }

  @Test
  void markRefunded_movesApprovedToRefunded() {
    RefundPo approved = refund(1L, RefundApplicationService.STATUS_APPROVED, new BigDecimal("50.00"));
    when(refundMapper.selectById(1L)).thenReturn(approved);

    RefundDto result = service.markRefunded(1L, "offline-refund-001", 9L);

    assertEquals(RefundApplicationService.STATUS_REFUNDED, result.status());
    assertEquals("offline-refund-001", result.providerRefundNo());
    verify(refundMapper).updateById(approved);
  }

  /** 退款退**原币种**：能读到原订单时取订单币种快照，而不是当前租户币种（§5/§6.3）。 */
  @Test
  void requestRefund_snapshotsOrderCurrencyAsOriginalCurrency() {
    OrderBillingMapper orderBillingMapper = mock(OrderBillingMapper.class);
    when(orderBillingMapper.selectCurrencyCode(1L, 9L)).thenReturn("CNY");
    RefundApplicationService serviceWithBilling =
        new RefundApplicationService(refundMapper, auditClient, orderBillingMapper);

    RefundDto result = serviceWithBilling.requestRefund(1L, 9L, new BigDecimal("50.00"), "多收退款", 7L);

    assertEquals("CNY", result.currencyCode());
  }

  @Test
  void approveRefund_rejectsWhenNotPending() {
    RefundPo rejected = refund(1L, RefundApplicationService.STATUS_REJECTED, new BigDecimal("50.00"));
    when(refundMapper.selectById(1L)).thenReturn(rejected);

    assertThrows(IllegalStateException.class,
        () -> service.approveRefund(1L, new BigDecimal("50.00"), 8L));
  }

  @Test
  void markRefunded_rejectsWhenNotApproved() {
    RefundPo pending = refund(1L, RefundApplicationService.STATUS_PENDING, new BigDecimal("50.00"));
    when(refundMapper.selectById(1L)).thenReturn(pending);

    assertThrows(IllegalStateException.class,
        () -> service.markRefunded(1L, "x", 9L));
  }

  @Test
  void approveRefund_rejectsWhenAmountExceedsRequest() {
    RefundPo pending = refund(1L, RefundApplicationService.STATUS_PENDING, new BigDecimal("50.00"));
    when(refundMapper.selectById(1L)).thenReturn(pending);

    assertThrows(IllegalStateException.class,
        () -> service.approveRefund(1L, new BigDecimal("60.00"), 8L));
  }

  @Test
  void approveRefund_rejectsMissingRefund() {
    when(refundMapper.selectById(999L)).thenReturn(null);

    assertThrows(IllegalStateException.class,
        () -> service.approveRefund(999L, new BigDecimal("50.00"), 8L));
  }

  private static RefundPo refund(Long id, String status, BigDecimal requestedAmount) {
    RefundPo po = new RefundPo();
    po.setId(id);
    po.setTenantId(1L);
    po.setOrderId(9L);
    po.setRequestId("req-" + id);
    po.setRequestedAmount(requestedAmount);
    po.setStatus(status);
    return po;
  }

  // ---------------------------------------------------------------------------------------------
  // 领域留痕：退款四步（申请/审批/驳回/线下登记）此前只有成功留痕，失败与成功断言都不完整。
  // ---------------------------------------------------------------------------------------------

  /** 申请退款成功：SUCCEEDED + 动作码 payment.refund.request。 */
  @Test
  void requestRefund_writesSucceededAudit() {
    service.requestRefund(1L, 9L, new BigDecimal("50.00"), "多收退款", 7L);

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment.refund.request", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
    assertNull(record.errorCode());
  }

  /** 申请退款失败（落库异常）：FAILURE + 异常类名，且不带幂等键。 */
  @Test
  void requestRefund_writesFailureAuditWithExceptionClassName() {
    when(refundMapper.insert(org.mockito.ArgumentMatchers.any(RefundPo.class)))
        .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_pay_refund_tenant_req"));

    assertThrows(org.springframework.dao.DuplicateKeyException.class,
        () -> service.requestRefund(1L, 9L, new BigDecimal("50.00"), "多收退款", 7L));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment.refund.request", record.action());
    assertEquals("退款申请", record.actionLabel());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("DuplicateKeyException", record.errorCode());
    assertNull(record.idempotencyKey());
    // 失败详情只留退款单/订单 ID，不含退款金额。
    assertFalse(record.detailJson().contains("50"), record.detailJson());
  }

  /** 审批通过失败（状态不是 PENDING）：FAILURE，errorCode 退化为异常类名。 */
  @Test
  void approveRefund_writesFailureAudit() {
    RefundPo rejected = refund(1L, RefundApplicationService.STATUS_REJECTED, new BigDecimal("50.00"));
    when(refundMapper.selectById(1L)).thenReturn(rejected);

    assertThrows(IllegalStateException.class,
        () -> service.approveRefund(1L, new BigDecimal("50.00"), 8L));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment.refund.approve", record.action());
    assertEquals("退款审批通过", record.actionLabel());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("IllegalStateException", record.errorCode());
    assertEquals("1", record.resourceId());
    assertNull(record.idempotencyKey());
  }

  /** 驳回失败（状态不是 PENDING）：FAILURE。 */
  @Test
  void rejectRefund_writesFailureAudit() {
    RefundPo approved = refund(1L, RefundApplicationService.STATUS_APPROVED, new BigDecimal("50.00"));
    when(refundMapper.selectById(1L)).thenReturn(approved);

    assertThrows(IllegalStateException.class, () -> service.rejectRefund(1L, 8L));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment.refund.reject", record.action());
    assertEquals("退款审批驳回", record.actionLabel());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("IllegalStateException", record.errorCode());
  }

  /** 线下退款登记失败（状态不是 APPROVED）：FAILURE，且详情不含线下退款单号。 */
  @Test
  void markRefunded_writesFailureAuditWithoutProviderRefundNo() {
    RefundPo pending = refund(1L, RefundApplicationService.STATUS_PENDING, new BigDecimal("50.00"));
    when(refundMapper.selectById(1L)).thenReturn(pending);

    assertThrows(IllegalStateException.class,
        () -> service.markRefunded(1L, "offline-refund-001", 9L));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment.refund.offline", record.action());
    assertEquals("线下退款登记", record.actionLabel());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("IllegalStateException", record.errorCode());
    assertFalse(record.detailJson().contains("offline-refund-001"), record.detailJson());
  }

  /** 审批通过成功：SUCCEEDED（成功路径既有留痕，这里补断言）。 */
  @Test
  void approveRefund_writesSucceededAudit() {
    RefundPo pending = refund(1L, RefundApplicationService.STATUS_PENDING, new BigDecimal("50.00"));
    when(refundMapper.selectById(1L)).thenReturn(pending);

    service.approveRefund(1L, new BigDecimal("50.00"), 8L);

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment.refund.approve", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
    assertNull(record.errorCode());
  }

  /** 驳回成功：SUCCEEDED。 */
  @Test
  void rejectRefund_writesSucceededAudit() {
    RefundPo pending = refund(1L, RefundApplicationService.STATUS_PENDING, new BigDecimal("50.00"));
    when(refundMapper.selectById(1L)).thenReturn(pending);

    service.rejectRefund(1L, 8L);

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment.refund.reject", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
  }

  /** 线下退款登记成功：SUCCEEDED。 */
  @Test
  void markRefunded_writesSucceededAudit() {
    RefundPo approved = refund(1L, RefundApplicationService.STATUS_APPROVED, new BigDecimal("50.00"));
    when(refundMapper.selectById(1L)).thenReturn(approved);

    service.markRefunded(1L, "offline-refund-001", 9L);

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment.refund.offline", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
  }

  private AuditClient.AuditRecord capturedAudit() {
    ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
    verify(auditClient).recordAsync(captor.capture());
    return captor.getValue();
  }
}
