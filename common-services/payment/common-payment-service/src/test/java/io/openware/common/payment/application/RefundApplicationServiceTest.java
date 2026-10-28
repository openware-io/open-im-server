package io.openware.common.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.payment.domain.billing.repository.OrderBillingRepository;
import io.openware.common.payment.domain.channel.repository.AuditSnapshot;
import io.openware.common.payment.domain.channel.repository.Persisted;
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
    when(refundRepository.insert(org.mockito.ArgumentMatchers.any(Refund.class), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(inv -> persisted(inv.getArgument(0)));
    when(refundRepository.updateStatusGuarded(org.mockito.ArgumentMatchers.any(Refund.class),
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(inv -> java.util.Optional.of(persisted(inv.getArgument(0))));
    when(refundRepository.findByRequestId(org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyString())).thenReturn(java.util.Optional.empty());
    when(refundRepository.findDuplicateCandidate(org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(java.util.Optional.empty());
  }

  @Test
  void requestRefund_createsPendingRefund() {
    RefundDto result = service.requestRefund(1L, 77L, 9L, new BigDecimal("50.00"), "多收退款", 7L);

    assertEquals(RefundApplicationService.STATUS_PENDING, result.status());
    // 退款币种快照：订单读不到（未注入账单查询）时回退当时租户币种，缺省 USD（§1/§5）。
    assertEquals("USD", result.currencyCode());
    ArgumentCaptor<Refund> captor = ArgumentCaptor.forClass(Refund.class);
    verify(refundRepository).insert(captor.capture(), org.mockito.ArgumentMatchers.any());
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
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(persisted(pending)));

    RefundDto result = service.approveRefund(1L, new BigDecimal("50.00"), 8L);

    assertEquals(RefundApplicationService.STATUS_APPROVED, result.status());
    assertEquals(0, new BigDecimal("50.00").compareTo(result.approvedAmount()));
    assertEquals(8L, result.approvedBy());
    verify(refundRepository).updateStatusGuarded(org.mockito.ArgumentMatchers.any(Refund.class), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void rejectRefund_movesPendingToRejected() {
    Refund pending = refund(1L, RefundApplicationService.STATUS_PENDING, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(persisted(pending)));

    RefundDto result = service.rejectRefund(1L, 8L);

    assertEquals(RefundApplicationService.STATUS_REJECTED, result.status());
    assertEquals(8L, result.approvedBy());
    verify(refundRepository).updateStatusGuarded(org.mockito.ArgumentMatchers.any(Refund.class), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void markRefunded_movesApprovedToRefunded() {
    Refund approved = refund(1L, RefundApplicationService.STATUS_APPROVED, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(persisted(approved)));

    RefundDto result = service.markRefunded(1L, "offline-refund-001", 9L);

    assertEquals(RefundApplicationService.STATUS_REFUNDED, result.status());
    assertEquals("offline-refund-001", result.providerRefundNo());
    verify(refundRepository).updateStatusGuarded(org.mockito.ArgumentMatchers.any(Refund.class), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  /** 退款退**原币种**：能读到原订单时取订单币种快照，而不是当前租户币种（§5/§6.3）。 */
  @Test
  void requestRefund_snapshotsOrderCurrencyAsOriginalCurrency() {
    OrderBillingRepository orderBillingRepository = mock(OrderBillingRepository.class);
    when(orderBillingRepository.findStoreId(1L, 9L)).thenReturn(java.util.Optional.of(77L));
    when(orderBillingRepository.findCurrencyCode(1L, 9L)).thenReturn(java.util.Optional.of("CNY"));
    RefundApplicationService serviceWithBilling =
        new RefundApplicationService(refundRepository, auditClient, orderBillingRepository);

    RefundDto result = serviceWithBilling.requestRefund(1L, 77L, 9L, new BigDecimal("50.00"), "多收退款", 7L);

    assertEquals("CNY", result.currencyCode());
  }

  @Test
  void approveRefund_rejectsWhenNotPending() {
    Refund rejected = refund(1L, RefundApplicationService.STATUS_REJECTED, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(persisted(rejected)));

    io.openware.common.exception.ApiException error = assertThrows(io.openware.common.exception.ApiException.class,
        () -> service.approveRefund(1L, new BigDecimal("50.00"), 8L));
    assertEquals(409, error.getStatus());
    assertEquals("REFUND_STATE_INVALID", error.getCode());
  }

  /**
   * P0-A 回归：重复登记线下退款必须是可判定的 409，而不是 500；且不得再次执行状态迁移/回冲。
   */
  @Test
  void markRefunded_duplicateAfterRefundedReturns409WithoutSecondMigration() {
    Refund refunded = refund(1L, RefundApplicationService.STATUS_REFUNDED, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(persisted(refunded)));

    io.openware.common.exception.ApiException error = assertThrows(io.openware.common.exception.ApiException.class,
        () -> service.markRefunded(1L, "offline-refund-001", 9L));

    assertEquals(409, error.getStatus());
    assertEquals("REFUND_STATE_INVALID", error.getCode());
    verify(refundRepository, org.mockito.Mockito.never()).updateStatusGuarded(
        org.mockito.ArgumentMatchers.any(Refund.class), org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  void markRefunded_rejectsWhenNotApproved() {
    Refund pending = refund(1L, RefundApplicationService.STATUS_PENDING, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(persisted(pending)));

    io.openware.common.exception.ApiException error = assertThrows(io.openware.common.exception.ApiException.class,
        () -> service.markRefunded(1L, "x", 9L));
    assertEquals(409, error.getStatus());
    assertEquals("REFUND_STATE_INVALID", error.getCode());
  }

  @Test
  void approveRefund_rejectsWhenAmountExceedsRequest() {
    Refund pending = refund(1L, RefundApplicationService.STATUS_PENDING, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(persisted(pending)));

    io.openware.common.exception.ApiException error = assertThrows(io.openware.common.exception.ApiException.class,
        () -> service.approveRefund(1L, new BigDecimal("60.00"), 8L));
    assertEquals(422, error.getStatus());
    assertEquals("REFUND_AMOUNT_EXCEEDS_REQUEST", error.getCode());
  }

  @Test
  void approveRefund_rejectsMissingRefund() {
    when(refundRepository.findById(999L)).thenReturn(java.util.Optional.empty());

    io.openware.common.exception.ApiException error = assertThrows(io.openware.common.exception.ApiException.class,
        () -> service.approveRefund(999L, new BigDecimal("50.00"), 8L));
    assertEquals(404, error.getStatus());
    assertEquals("REFUND_NOT_FOUND", error.getCode());
  }

  /** P0-B：同 Idempotency-Key 重放返回首次创建的退款单（同 id），不新建第二条。 */
  @Test
  void requestRefund_sameIdempotencyKeyReplaysExistingRow() {
    Refund existing = refund(5L, RefundApplicationService.STATUS_PENDING, new BigDecimal("50.00"));
    when(refundRepository.findByRequestId(1L, "idem-key-1"))
        .thenReturn(java.util.Optional.of(persisted(existing)));

    RefundDto result = service.requestRefund(1L, 77L, 9L, new BigDecimal("50.00"), "多收退款", 7L, "idem-key-1");

    assertEquals(5L, result.id());
    verify(refundRepository, org.mockito.Mockito.never()).insert(
        org.mockito.ArgumentMatchers.any(Refund.class), org.mockito.ArgumentMatchers.any());
  }

  /** P0-B：同键不同 payload 必须 409，而不是静默返回不匹配的退款单。 */
  @Test
  void requestRefund_reusedKeyWithDifferentAmountConflicts() {
    Refund existing = refund(5L, RefundApplicationService.STATUS_PENDING, new BigDecimal("50.00"));
    when(refundRepository.findByRequestId(1L, "idem-key-2"))
        .thenReturn(java.util.Optional.of(persisted(existing)));

    io.openware.common.exception.ApiException error = assertThrows(io.openware.common.exception.ApiException.class,
        () -> service.requestRefund(1L, 77L, 9L, new BigDecimal("40.00"), "多收退款", 7L, "idem-key-2"));

    assertEquals(409, error.getStatus());
    assertEquals("IDEMPOTENCY_KEY_REUSED", error.getCode());
    verify(refundRepository, org.mockito.Mockito.never()).insert(
        org.mockito.ArgumentMatchers.any(Refund.class), org.mockito.ArgumentMatchers.any());
  }

  /** P0-B：不带幂等键时，同订单同金额已有退款单（PENDING/APPROVED/REFUNDED）→ 409 且不新建。 */
  @Test
  void requestRefund_duplicateActiveSameAmountConflictsWithoutInsert() {
    Refund existing = refund(5L, RefundApplicationService.STATUS_PENDING, new BigDecimal("50.00"));
    when(refundRepository.findDuplicateCandidate(1L, 9L, new BigDecimal("50.00")))
        .thenReturn(java.util.Optional.of(persisted(existing)));

    io.openware.common.exception.ApiException error = assertThrows(io.openware.common.exception.ApiException.class,
        () -> service.requestRefund(1L, 77L, 9L, new BigDecimal("50.00"), "多收退款", 7L));

    assertEquals(409, error.getStatus());
    assertEquals("REFUND_DUPLICATE", error.getCode());
    verify(refundRepository, org.mockito.Mockito.never()).insert(
        org.mockito.ArgumentMatchers.any(Refund.class), org.mockito.ArgumentMatchers.any());
  }

  /** P0-B：前一条同额退款已 REFUNDED 时再次申请 → 409（不得新建"退款后退款"脏行）。 */
  @Test
  void requestRefund_duplicateAfterRefundedConflictsWithoutInsert() {
    Refund refunded = refund(5L, RefundApplicationService.STATUS_REFUNDED, new BigDecimal("50.00"));
    when(refundRepository.findDuplicateCandidate(1L, 9L, new BigDecimal("50.00")))
        .thenReturn(java.util.Optional.of(persisted(refunded)));

    io.openware.common.exception.ApiException error = assertThrows(io.openware.common.exception.ApiException.class,
        () -> service.requestRefund(1L, 77L, 9L, new BigDecimal("50.00"), "重复退款", 7L));

    assertEquals(409, error.getStatus());
    assertEquals("REFUND_DUPLICATE", error.getCode());
    verify(refundRepository, org.mockito.Mockito.never()).insert(
        org.mockito.ArgumentMatchers.any(Refund.class), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void requestRefund_rejectsNonPositiveAmount() {
    io.openware.common.exception.ApiException error = assertThrows(io.openware.common.exception.ApiException.class,
        () -> service.requestRefund(1L, 77L, 9L, BigDecimal.ZERO, "非法金额", 7L));

    assertEquals(422, error.getStatus());
    assertEquals("REFUND_AMOUNT_INVALID", error.getCode());
  }

  private static Refund refund(Long id, String status, BigDecimal requestedAmount) {
    return new Refund(id, 1L, 77L, "KTV", 9L, null, "req-" + id, requestedAmount, null, "USD", null,
        status, null, 7L, null);
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
    when(refundRepository.insert(org.mockito.ArgumentMatchers.any(Refund.class), org.mockito.ArgumentMatchers.any()))
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

  /** 审批通过失败（状态不是 PENDING）：FAILURE，errorCode 取稳定业务码 REFUND_STATE_INVALID。 */
  @Test
  void approveRefund_writesFailureAudit() {
    Refund rejected = refund(1L, RefundApplicationService.STATUS_REJECTED, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(persisted(rejected)));

    assertThrows(io.openware.common.exception.ApiException.class,
        () -> service.approveRefund(1L, new BigDecimal("50.00"), 8L));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment.refund.approve", record.action());
    assertEquals("退款审批通过", record.actionLabel());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("REFUND_STATE_INVALID", record.errorCode());
    assertEquals("1", record.resourceId());
    assertNull(record.idempotencyKey());
  }

  /** 驳回失败（状态不是 PENDING）：FAILURE。 */
  @Test
  void rejectRefund_writesFailureAudit() {
    Refund approved = refund(1L, RefundApplicationService.STATUS_APPROVED, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(persisted(approved)));

    assertThrows(io.openware.common.exception.ApiException.class, () -> service.rejectRefund(1L, 8L));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment.refund.reject", record.action());
    assertEquals("退款审批驳回", record.actionLabel());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("REFUND_STATE_INVALID", record.errorCode());
  }

  /** 线下退款登记失败（状态不是 APPROVED）：FAILURE，且详情不含线下退款单号。 */
  @Test
  void markRefunded_writesFailureAuditWithoutProviderRefundNo() {
    Refund pending = refund(1L, RefundApplicationService.STATUS_PENDING, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(persisted(pending)));

    assertThrows(io.openware.common.exception.ApiException.class,
        () -> service.markRefunded(1L, "offline-refund-001", 9L));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment.refund.offline", record.action());
    assertEquals("线下退款登记", record.actionLabel());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("REFUND_STATE_INVALID", record.errorCode());
    assertFalse(record.detailJson().contains("offline-refund-001"), record.detailJson());
  }

  /** 审批通过成功：SUCCEEDED（成功路径既有留痕，这里补断言）。 */
  @Test
  void approveRefund_writesSucceededAudit() {
    Refund pending = refund(1L, RefundApplicationService.STATUS_PENDING, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(persisted(pending)));

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
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(persisted(pending)));

    service.rejectRefund(1L, 8L);

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment.refund.reject", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
  }

  /** 线下退款登记成功：SUCCEEDED。 */
  @Test
  void markRefunded_writesSucceededAudit() {
    Refund approved = refund(1L, RefundApplicationService.STATUS_APPROVED, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(persisted(approved)));

    service.markRefunded(1L, "offline-refund-001", 9L);

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment.refund.offline", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
  }

  @Test
  void approveRefund_rejectsNullStoreContext() {
    Refund pending = refund(1L, RefundApplicationService.STATUS_PENDING, new BigDecimal("50.00"));
    when(refundRepository.findById(1L)).thenReturn(java.util.Optional.of(persisted(pending)));
    io.openware.infrastructure.tenant.TenantContextHolder.set(
        new io.openware.infrastructure.tenant.TenantContext(1L, 1L, null, 0L, 0));
    try {
      assertThrows(io.openware.common.exception.ApiException.class,
          () -> service.approveRefund(1L, new BigDecimal("50.00"), 8L));
    } finally {
      io.openware.infrastructure.tenant.TenantContextHolder.clear();
    }
  }

  private static Persisted<Refund> persisted(Refund refund) {
    return new Persisted<>(refund, new AuditSnapshot(null, null));
  }

  private AuditClient.AuditRecord capturedAudit() {
    ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
    verify(auditClient).recordAsync(captor.capture());
    return captor.getValue();
  }
}
