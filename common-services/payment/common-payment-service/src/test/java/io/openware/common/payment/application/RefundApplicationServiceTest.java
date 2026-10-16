package io.openware.common.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.payment.domain.billing.repository.OrderBillingRepository;
import io.openware.common.payment.domain.refund.model.Refund;
import io.openware.common.payment.domain.refund.repository.RefundRepository;
import io.openware.infrastructure.audit.AuditClient;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 退款审批状态机：PENDING → APPROVED/REJECTED → REFUNDED，含非法转换拒绝。 */
class RefundApplicationServiceTest {

    private final RefundRepository refundRepository = mock(RefundRepository.class);
  private final AuditClient auditClient = mock(AuditClient.class);
  private final RefundApplicationService service = new RefundApplicationService(refundRepository, auditClient);

  /**
   * 端口替身默认行为：新增返回落库后的聚合；状态守卫成功（等于"库中这一行仍是期望状态"）。
   *
   * <p>失败用例各自覆盖这两处来模拟并发抢先或落库异常。
   */
  @org.junit.jupiter.api.BeforeEach
  void stubPortDefaults() {
    when(refundRepository.insert(org.mockito.ArgumentMatchers.any(Refund.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(refundRepository.updateStatusGuarded(org.mockito.ArgumentMatchers.any(Refund.class),
        org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
  }

  @Test
  void requestRefund_createsPendingRefund() {
    RefundDto result = service.requestRefund(1L, 77L, 9L, new BigDecimal("50.00"), "多收退款", 7L);

    assertEquals(RefundApplicationService.STATUS_PENDING, result.status());
    // 退款币种快照：订单读不到（未注入账单查询）时回退当时租户币种，缺省 USD（§1/§5）。
    assertEquals("USD", result.currencyCode());
    ArgumentCaptor<Refund> captor = ArgumentCaptor.forClass(Refund.class);
    verify(refundRepository).insert(captor.capture());
    assertEquals(RefundApplicationService.STATUS_PENDING, captor.getValue().status());
    assertEquals(77L, captor.getValue().storeId());
    assertEquals(0, new BigDecimal("50.00").compareTo(captor.getValue().requestedAmount()));
  }

  @Test
  void requestRefund_keepsExistingApprovalRequiredStateMachine() {
    PaymentRuleApplicationService rules = mock(PaymentRuleApplicationService.class);
    when(rules.resolveRefund(1L, 77L, null))
        .thenReturn(new PaymentRuleApplicationService.RefundRuleView(new BigDecimal("100.00"), true, "STORE", 2));
    RefundApplicationService configured = new RefundApplicationService(refundRepository, auditClient, null, rules);

    RefundDto result = configured.requestRefund(1L, 77L, 9L, new BigDecimal("50.00"), "小额退款", 7L);

    assertEquals(RefundApplicationService.STATUS_PENDING, result.status());
  }

  @Test
  void approveRefund_movesPendingToApproved() {
    Refund pending = refund(1L, RefundApplicationService.STATUS_PENDING, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(pending));

    RefundDto result = service.approveRefund(1L, new BigDecimal("50.00"), 8L);

    assertEquals(RefundApplicationService.STATUS_APPROVED, result.status());
    assertEquals(0, new BigDecimal("50.00").compareTo(result.approvedAmount()));
    assertEquals(8L, result.approvedBy());
    verify(refundRepository).updateStatusGuarded(org.mockito.ArgumentMatchers.any(Refund.class), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void rejectRefund_movesPendingToRejected() {
    Refund pending = refund(1L, RefundApplicationService.STATUS_PENDING, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(pending));

    RefundDto result = service.rejectRefund(1L, 8L);

    assertEquals(RefundApplicationService.STATUS_REJECTED, result.status());
    assertEquals(8L, result.approvedBy());
    verify(refundRepository).updateStatusGuarded(org.mockito.ArgumentMatchers.any(Refund.class), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void markRefunded_movesApprovedToRefunded() {
    Refund approved = refund(1L, RefundApplicationService.STATUS_APPROVED, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(approved));

    RefundDto result = service.markRefunded(1L, "offline-refund-001", 9L);

    assertEquals(RefundApplicationService.STATUS_REFUNDED, result.status());
    assertEquals("offline-refund-001", result.providerRefundNo());
    verify(refundRepository).updateStatusGuarded(org.mockito.ArgumentMatchers.any(Refund.class), org.mockito.ArgumentMatchers.anyString());
  }

  /** 退款退**原币种**：能读到原订单时取订单币种快照，而不是当前租户币种（§5/§6.3）。 */
  @Test
  void requestRefund_snapshotsOrderCurrencyAsOriginalCurrency() {
    OrderBillingRepository orderBillingRepository = mock(OrderBillingRepository.class);
    when(orderBillingRepository.findCurrencyCode(1L, 9L)).thenReturn(java.util.Optional.of("CNY"));
    RefundApplicationService serviceWithBilling =
        new RefundApplicationService(refundRepository, auditClient, orderBillingRepository);

    RefundDto result = serviceWithBilling.requestRefund(1L, 77L, 9L, new BigDecimal("50.00"), "多收退款", 7L);

    assertEquals("CNY", result.currencyCode());
  }

  @Test
  void approveRefund_rejectsWhenNotPending() {
    Refund rejected = refund(1L, RefundApplicationService.STATUS_REJECTED, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(rejected));

    assertThrows(IllegalStateException.class,
        () -> service.approveRefund(1L, new BigDecimal("50.00"), 8L));
  }

  @Test
  void markRefunded_rejectsWhenNotApproved() {
    Refund pending = refund(1L, RefundApplicationService.STATUS_PENDING, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(pending));

    assertThrows(IllegalStateException.class,
        () -> service.markRefunded(1L, "x", 9L));
  }

  @Test
  void approveRefund_rejectsWhenAmountExceedsRequest() {
    Refund pending = refund(1L, RefundApplicationService.STATUS_PENDING, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(pending));

    assertThrows(IllegalStateException.class,
        () -> service.approveRefund(1L, new BigDecimal("60.00"), 8L));
  }

  @Test
  void approveRefund_rejectsMissingRefund() {
    when(refundRepository.findById(999L)).thenReturn(java.util.Optional.empty());

    assertThrows(IllegalStateException.class,
        () -> service.approveRefund(999L, new BigDecimal("50.00"), 8L));
  }

  private static Refund refund(Long id, String status, BigDecimal requestedAmount) {
    return new Refund(id, 1L, 77L, "KTV", 9L, null, "req-" + id, requestedAmount, null, "USD", null,
        status, null, 7L, null, null, null);
  }

  // ---------------------------------------------------------------------------------------------
  // 领域留痕：退款四步（申请/审批/驳回/线下登记）此前只有成功留痕，失败与成功断言都不完整。
  // ---------------------------------------------------------------------------------------------

  /** 申请退款成功：SUCCEEDED + 动作码 payment.refund.request。 */
  @Test
  void requestRefund_writesSucceededAudit() {
    service.requestRefund(1L, 77L, 9L, new BigDecimal("50.00"), "多收退款", 7L);

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment.refund.request", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
    assertNull(record.errorCode());
  }

  /** 申请退款失败（落库异常）：FAILURE + 异常类名，且不带幂等键。 */
  @Test
  void requestRefund_writesFailureAuditWithExceptionClassName() {
    when(refundRepository.insert(org.mockito.ArgumentMatchers.any(Refund.class)))
        .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_pay_refund_tenant_req"));

    assertThrows(org.springframework.dao.DuplicateKeyException.class,
        () -> service.requestRefund(1L, 77L, 9L, new BigDecimal("50.00"), "多收退款", 7L));

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
    Refund rejected = refund(1L, RefundApplicationService.STATUS_REJECTED, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(rejected));

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
    Refund approved = refund(1L, RefundApplicationService.STATUS_APPROVED, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(approved));

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
    Refund pending = refund(1L, RefundApplicationService.STATUS_PENDING, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(pending));

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
    Refund pending = refund(1L, RefundApplicationService.STATUS_PENDING, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(pending));

    service.approveRefund(1L, new BigDecimal("50.00"), 8L);

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment.refund.approve", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
    assertNull(record.errorCode());
  }

  /** 驳回成功：SUCCEEDED。 */
  @Test
  void rejectRefund_writesSucceededAudit() {
    Refund pending = refund(1L, RefundApplicationService.STATUS_PENDING, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(pending));

    service.rejectRefund(1L, 8L);

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment.refund.reject", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
  }

  /** 线下退款登记成功：SUCCEEDED。 */
  @Test
  void markRefunded_writesSucceededAudit() {
    Refund approved = refund(1L, RefundApplicationService.STATUS_APPROVED, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(approved));

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
