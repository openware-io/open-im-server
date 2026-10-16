package io.openware.common.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.exception.ApiException;
import io.openware.common.payment.infra.client.CustomerClient;
import io.openware.common.payment.domain.billing.model.OrderReceivable;
import io.openware.common.payment.domain.billing.repository.OrderBillingRepository;
import io.openware.common.payment.domain.collect.model.PayCollect;
import io.openware.common.payment.domain.collect.repository.PayCollectRepository;
import io.openware.common.payment.domain.event.repository.PaymentEventOutbox;
import io.openware.common.payment.domain.intent.model.PaymentIntent;
import io.openware.common.payment.domain.intent.repository.PaymentIntentRepository;
import io.openware.common.payment.domain.transaction.model.PaymentTransaction;
import io.openware.common.payment.domain.transaction.repository.PaymentTransactionRepository;
import io.openware.infrastructure.audit.AuditClient;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 组合收款：抵扣顺序「优惠→积分→储值→现金」、前置金额校验（不满足不落任何行）、
 * 现金分腿与 pay_collect 状态机同事务（失败一起回滚）、跨服务补偿、幂等重放与失败重跑。
 */
class CollectApplicationServiceTest {

  private final PaymentIntentRepository paymentIntentRepository = mock(PaymentIntentRepository.class);
  private final PaymentTransactionRepository paymentTransactionRepository =
      mock(PaymentTransactionRepository.class);
  private final PayCollectRepository payCollectRepository = mock(PayCollectRepository.class);
  private final CustomerClient customerClient = mock(CustomerClient.class);
  private final PaymentMethodApplicationService paymentMethodService = mock(PaymentMethodApplicationService.class);
  private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
  private final AuditClient auditClient = mock(AuditClient.class);
  private final OrderBillingRepository orderBillingRepository = mock(OrderBillingRepository.class);
  private final PaymentEventOutbox eventOutbox = mock(PaymentEventOutbox.class);
  private final CollectApplicationService service = new CollectApplicationService(paymentIntentRepository,
      paymentTransactionRepository, payCollectRepository, customerClient, paymentMethodService,
      transactionManager, auditClient, orderBillingRepository, eventOutbox);

  @BeforeEach
  void allowPaymentMethods() {
    when(paymentMethodService.isTenantAllowed(anyLong(), any(), any())).thenReturn(true);
    // 默认「无账单可校验」，与原先 selectForUpdate 返回 null 等价。
    when(orderBillingRepository.findForUpdate(anyLong(), anyLong())).thenReturn(Optional.empty());
    // 端口替身默认行为：新增回填主键、状态写入成功。
    // 端口方法本身返回聚合/布尔，不桩设会得到 null/false，从而把成功路径变成失败路径。
    when(payCollectRepository.insert(any(PayCollect.class)))
        .thenAnswer(inv -> withId(inv.getArgument(0), 1L));
    when(payCollectRepository.updateStateAndSnapshot(any(PayCollect.class))).thenReturn(true);
    when(paymentIntentRepository.insert(any(PaymentIntent.class)))
        .thenAnswer(inv -> withId(inv.getArgument(0)));
    when(paymentTransactionRepository.insert(any(PaymentTransaction.class)))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  /** 阻塞到赢家确认落库；超时后返回，由调用方的断言报告失败。 */
  private static void awaitConfirmed(CountDownLatch confirmed) {
    try {
      confirmed.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** 回填主键（真实适配器由自增列回填，测试替身等价模拟）。 */
  private static PayCollect withId(PayCollect collect, Long id) {
    return new PayCollect(id, collect.tenantId(), collect.storeId(), collect.businessType(),
        collect.collectNo(), collect.orderId(), collect.idempotencyKey(), collect.state(),
        collect.currencyCode(), collect.requestJson(), collect.responseJson(), collect.createdAt(),
        collect.updatedAt());
  }

  /** 回填主键：交易行要用它做 {@code paymentIntentId}。 */
  private static PaymentIntent withId(PaymentIntent intent) {
    return new PaymentIntent(41L, intent.tenantId(), intent.storeId(), intent.orderId(),
        intent.merchantAccountId(), intent.provider(), intent.paymentMethod(), intent.amount(),
        intent.currencyCode(), intent.status(), intent.idempotencyKey(), intent.expiresAt(),
        intent.createdAt(), intent.updatedAt());
  }

  /** 账单夹具：只带本域真正读取的列。 */
  private static OrderReceivable bill(long total, long paid, String status) {
    return new OrderReceivable(9L, status, java.math.BigDecimal.valueOf(total),
        java.math.BigDecimal.valueOf(paid), "CNY");
  }

  /** 收款单夹具（幂等记录）。 */
  private static PayCollect collect(Long id, String collectNo, String state, String responseJson) {
    var now = java.time.LocalDateTime.now();
    return new PayCollect(id, 1L, 2L, null, collectNo, 9L, "key", state, "CNY", "{}", responseJson, now, now);
  }

  @Test
  void collect_rejectsClientPayableWhenItDiffersFromServerBill() {
    OrderReceivable bill = bill(10000, 0, "WAITING_SETTLEMENT");
    when(orderBillingRepository.findForUpdate(1L, 9L)).thenReturn(Optional.of(bill));
    when(orderBillingRepository.markPaid(anyLong(), anyLong(), any(), any(), any())).thenReturn(true);

    ApiException ex = assertThrows(ApiException.class, () -> service.collect(1L, 2L, 9L, 7L,
        "CNY", 1L, List.of(), "key"));

    assertEquals("PAYMENT_AMOUNT_MISMATCH", ex.getCode());
    verify(payCollectRepository, never()).insert(any(PayCollect.class));
  }

  @Test
  void collect_marksOrderCompletedAfterFullPayment() {
    OrderReceivable bill = bill(5000, 0, "WAITING_SETTLEMENT");
    when(orderBillingRepository.findForUpdate(1L, 9L)).thenReturn(Optional.of(bill));
    when(orderBillingRepository.markPaid(anyLong(), anyLong(), any(), any(), any())).thenReturn(true);

    service.collect(1L, 2L, 9L, 7L, "CNY", 5000L,
        List.of(new CollectApplicationService.PaymentItem("CASH", 5000L)), "key");

    verify(orderBillingRepository).markPaid(1L, 9L, java.math.BigDecimal.ZERO,
        java.math.BigDecimal.valueOf(5000), java.math.BigDecimal.valueOf(5000));
  }

  @Test
  void collect_deductsInOrderPointWalletCash() {
    when(payCollectRepository.findByIdempotencyKey(any(), any())).thenReturn(Optional.empty());
    List<CollectApplicationService.PaymentItem> payments = List.of(
        new CollectApplicationService.PaymentItem("CASH", 5000L),
        new CollectApplicationService.PaymentItem("WALLET", 3000L),
        new CollectApplicationService.PaymentItem("POINT", 2000L));

    CollectApplicationService.CollectResult result =
        service.collect(1L, 2L, 9L, 7L, "CNY", 10000L, payments, "key");

    assertEquals(0L, result.remainingAmount());
    assertEquals(List.of(
        new CollectApplicationService.CollectedMethod("POINT", 2000L),
        new CollectApplicationService.CollectedMethod("WALLET", 3000L),
        new CollectApplicationService.CollectedMethod("CASH", 5000L)), result.collectedByMethod());

    ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
    InOrder order = inOrder(customerClient, paymentIntentRepository);
    order.verify(customerClient).redeemPoints(eq(1L), eq(2L), eq(7L), eq(2000L), eq(9L), keys.capture());
    order.verify(customerClient).deductWallet(eq(1L), eq(2L), eq(7L), eq(3000L), eq("CNY"), eq(9L), keys.capture());
    order.verify(paymentIntentRepository).insert(any(PaymentIntent.class));

    // 同一笔收款的三条分腿共用一个「尝试键」前缀，且各自带分腿后缀（不再直接用客户端幂等键，
    // 以便失败重跑时不会撞上 customer 侧已消费的幂等键）。现金类分腿后缀带序号。
    String attempt = keys.getAllValues().get(0).replace(":POINT", "");
    assertEquals(attempt + ":WALLET", keys.getAllValues().get(1));
    ArgumentCaptor<PaymentIntent> intent = ArgumentCaptor.forClass(PaymentIntent.class);
    verify(paymentIntentRepository).insert(intent.capture());
    assertEquals(attempt + ":CASH:1", intent.getValue().idempotencyKey());
  }

  /**
   * 同一笔收款拆两笔现金类分腿（ALIPAY + CASH）：两腿运行结果都进入 collectedByMethod，
   * 且各自生成互不相同的分腿幂等键（此前统一用 `:CASH`，第二腿会撞生产唯一键 uk_pay_intent_tenant_idem）。
   */
  @Test
  void collect_alipayAndCashSplitUsesDistinctIdempotencyKeysPerLeg() {
    when(payCollectRepository.findByIdempotencyKey(any(), any())).thenReturn(Optional.empty());
    when(paymentIntentRepository.insert(any(PaymentIntent.class))).thenAnswer(inv -> {
      PaymentIntent po = inv.getArgument(0);
      // 端口返回落库后的聚合（含回填主键）：交易行要用它做 paymentIntentId。
      return new PaymentIntent(po.id() == null ? 1L : po.id() + 1, po.tenantId(), po.storeId(),
          po.orderId(), po.merchantAccountId(), po.provider(), po.paymentMethod(), po.amount(),
          po.currencyCode(), po.status(), po.idempotencyKey(), po.expiresAt(), po.createdAt(),
          po.updatedAt());
    });

    CollectApplicationService.CollectResult result = service.collect(1L, 2L, 9L, 7L, "CNY", 8000L,
        List.of(new CollectApplicationService.PaymentItem("ALIPAY", 3000L),
            new CollectApplicationService.PaymentItem("CASH", 5000L)), "key");

    assertEquals(List.of(
        new CollectApplicationService.CollectedMethod("ALIPAY", 3000L),
        new CollectApplicationService.CollectedMethod("CASH", 5000L)), result.collectedByMethod());

    ArgumentCaptor<PaymentIntent> intents = ArgumentCaptor.forClass(PaymentIntent.class);
    verify(paymentIntentRepository, times(2)).insert(intents.capture());
    String first = intents.getAllValues().get(0).idempotencyKey();
    String second = intents.getAllValues().get(1).idempotencyKey();
    assertNotEquals(first, second, "同一笔收款的两笔现金类分腿幂等键必须不同");
    assertTrue(first.endsWith(":CASH:1"), first);
    assertTrue(second.endsWith(":CASH:2"), second);
    assertEquals(first.substring(0, first.length() - 1) + "2", second);
    assertTrue(first.length() < 64 && second.length() < 64, "幂等键长度必须小于 64：" + first + " / " + second);
    verify(paymentTransactionRepository, times(2)).insert(any(PaymentTransaction.class));
  }

  @Test
  void collect_rejectsLegSumBelowPayableWithoutAnyPersistence() {
    when(payCollectRepository.findByIdempotencyKey(any(), any())).thenReturn(Optional.empty());
    List<CollectApplicationService.PaymentItem> payments = List.of(
        new CollectApplicationService.PaymentItem("POINT", 2000L),
        new CollectApplicationService.PaymentItem("CASH", 4000L));

    ApiException ex = assertThrows(ApiException.class,
        () -> service.collect(1L, 2L, 9L, 7L, "CNY", 8000L, payments, "key"));

    assertEquals(422, ex.getStatus());
    assertEquals("PAYMENT_INCOMPLETE", ex.getCode());
    assertNoPersistenceAndNoDownstreamCall();
  }

  @Test
  void collect_rejectsLegSumAbovePayableWithoutAnyPersistence() {
    when(payCollectRepository.findByIdempotencyKey(any(), any())).thenReturn(Optional.empty());
    List<CollectApplicationService.PaymentItem> payments = List.of(
        new CollectApplicationService.PaymentItem("POINT", 2000L),
        new CollectApplicationService.PaymentItem("CASH", 7000L));

    ApiException ex = assertThrows(ApiException.class,
        () -> service.collect(1L, 2L, 9L, 7L, "CNY", 8000L, payments, "key"));

    assertEquals(422, ex.getStatus());
    assertEquals("PAYMENT_AMOUNT_MISMATCH", ex.getCode());
    assertNoPersistenceAndNoDownstreamCall();
  }

  @Test
  void collect_rejectsNegativeLegAmountWithoutAnyPersistence() {
    when(payCollectRepository.findByIdempotencyKey(any(), any())).thenReturn(Optional.empty());
    List<CollectApplicationService.PaymentItem> payments = List.of(
        new CollectApplicationService.PaymentItem("CASH", -100L),
        new CollectApplicationService.PaymentItem("CASH", 8100L));

    ApiException ex = assertThrows(ApiException.class,
        () -> service.collect(1L, 2L, 9L, 7L, "CNY", 8000L, payments, "key"));

    assertEquals(422, ex.getStatus());
    assertEquals("PAYMENT_AMOUNT_MISMATCH", ex.getCode());
    assertNoPersistenceAndNoDownstreamCall();
  }

  @Test
  void collect_rejectsUnknownMethodWithoutAnyPersistence() {
    when(payCollectRepository.findByIdempotencyKey(any(), any())).thenReturn(Optional.empty());
    List<CollectApplicationService.PaymentItem> payments =
        List.of(new CollectApplicationService.PaymentItem("BITCOIN", 8000L));

    ApiException ex = assertThrows(ApiException.class,
        () -> service.collect(1L, 2L, 9L, 7L, "CNY", 8000L, payments, "key"));

    assertEquals(422, ex.getStatus());
    assertEquals("PAYMENT_METHOD_UNKNOWN", ex.getCode());
    assertNoPersistenceAndNoDownstreamCall();
  }

  @Test
  void collect_rejectsUnauthorizedMethodWithoutAnyPersistence() {
    when(payCollectRepository.findByIdempotencyKey(any(), any())).thenReturn(Optional.empty());
    when(paymentMethodService.isTenantAllowed(1L, 2L, "POINT")).thenReturn(false);

    ApiException ex = assertThrows(ApiException.class, () -> service.collect(1L, 2L, 9L, 7L,
        "CNY", 8000L, List.of(new CollectApplicationService.PaymentItem("POINT", 8000L)), "key"));

    assertEquals(422, ex.getStatus());
    assertEquals("PAYMENT_METHOD_NOT_GRANTED", ex.getCode());
    assertNoPersistenceAndNoDownstreamCall();
  }

  @Test
  void collect_returnsFirstResultForSameIdempotencyKey() throws Exception {
    CollectApplicationService.CollectResult first =
        new CollectApplicationService.CollectResult(0L,
            List.of(new CollectApplicationService.CollectedMethod("CASH", 5000L)));
    PayCollect existing = collect(1L, "PC-1", PayCollect.STATE_CONFIRMED, new ObjectMapper().writeValueAsString(first));
    when(payCollectRepository.findByIdempotencyKey(any(), any())).thenReturn(Optional.of(existing));

    CollectApplicationService.CollectResult result =
        service.collect(1L, 2L, 9L, 7L, "CNY", 5000L, List.of(), "key");

    assertEquals(0L, result.remainingAmount());
    assertEquals(List.of(new CollectApplicationService.CollectedMethod("CASH", 5000L)),
        result.collectedByMethod());
    verify(payCollectRepository, never()).insert(any(PayCollect.class));
    verify(customerClient, never()).redeemPoints(any(), any(), any(), any(), any(), any());
  }

  @Test
  void collect_replaysFirstResultEvenWhenOrderIsAlreadyPaid() throws Exception {
    // 首次成功已把订单置为 COMPLETED、payable 归零；重放必须命中幂等记录，而不是被服务端应收校验拒掉。
    OrderReceivable bill = bill(8000, 8000, "COMPLETED");
    when(orderBillingRepository.findForUpdate(1L, 9L)).thenReturn(Optional.of(bill));
    CollectApplicationService.CollectResult first =
        new CollectApplicationService.CollectResult(0L,
            List.of(new CollectApplicationService.CollectedMethod("CASH", 8000L)));
    PayCollect existing = collect(1L, "PC-1", PayCollect.STATE_CONFIRMED, new ObjectMapper().writeValueAsString(first));
    when(payCollectRepository.findByIdempotencyKey(any(), any())).thenReturn(Optional.of(existing));

    CollectApplicationService.CollectResult result =
        service.collect(1L, 2L, 9L, 7L, "CNY", 8000L, List.of(), "key");

    assertEquals(first, result);
    verify(orderBillingRepository, never()).markPaid(anyLong(), anyLong(), any(), any(), any());
    verify(paymentIntentRepository, never()).insert(any(PaymentIntent.class));
  }

  @Test
  void collect_throws409WhenSameKeyIsProcessing() {
    PayCollect existing = collect(1L, "PC-1", "HOLD", null);
    when(payCollectRepository.findByIdempotencyKey(any(), any())).thenReturn(Optional.of(existing));

    ApiException ex = assertThrows(ApiException.class,
        () -> service.collect(1L, 2L, 9L, 7L, "CNY", 5000L, List.of(), "key"));

    assertEquals(409, ex.getStatus());
    assertEquals("PAYMENT_PROCESSING", ex.getCode());
    assertNoPersistenceAndNoDownstreamCall();
  }

  @Test
  void collect_throws422WhenPointExceedsRemaining() {
    when(payCollectRepository.findByIdempotencyKey(any(), any())).thenReturn(Optional.empty());
    List<CollectApplicationService.PaymentItem> payments =
        List.of(new CollectApplicationService.PaymentItem("POINT", 6000L));

    ApiException ex = assertThrows(ApiException.class,
        () -> service.collect(1L, 2L, 9L, 7L, "CNY", 5000L, payments, "key"));

    assertEquals(422, ex.getStatus());
    assertEquals("PAYMENT_AMOUNT_MISMATCH", ex.getCode());
    assertNoPersistenceAndNoDownstreamCall();
  }

  @Test
  void collect_throws422WhenCashExceedsRemaining() {
    when(payCollectRepository.findByIdempotencyKey(any(), any())).thenReturn(Optional.empty());
    List<CollectApplicationService.PaymentItem> payments =
        List.of(new CollectApplicationService.PaymentItem("CASH", 6000L));

    ApiException ex = assertThrows(ApiException.class,
        () -> service.collect(1L, 2L, 9L, 7L, "CNY", 5000L, payments, "key"));

    assertEquals(422, ex.getStatus());
    assertEquals("PAYMENT_AMOUNT_MISMATCH", ex.getCode());
    assertNoPersistenceAndNoDownstreamCall();
  }

  @Test
  void collect_compensatesHeldLegsAndWritesFailedSnapshotOnMidLegFailure() {
    when(payCollectRepository.findByIdempotencyKey(any(), any())).thenReturn(Optional.empty());
    doThrow(new ApiException(422, "LEDGER_INSUFFICIENT", "储值余额不足"))
        .when(customerClient).deductWallet(any(), any(), any(), any(), any(), any(), any());
    List<CollectApplicationService.PaymentItem> payments = List.of(
        new CollectApplicationService.PaymentItem("POINT", 2000L),
        new CollectApplicationService.PaymentItem("WALLET", 3000L),
        new CollectApplicationService.PaymentItem("CASH", 3000L));

    ApiException ex = assertThrows(ApiException.class,
        () -> service.collect(1L, 2L, 9L, 7L, "CNY", 8000L, payments, "key"));

    assertEquals("LEDGER_INSUFFICIENT", ex.getCode());
    // 已扣减的积分按同一次尝试的键归还
    ArgumentCaptor<String> redeemKey = ArgumentCaptor.forClass(String.class);
    verify(customerClient).redeemPoints(eq(1L), eq(2L), eq(7L), eq(2000L), eq(9L), redeemKey.capture());
    ArgumentCaptor<String> releaseKey = ArgumentCaptor.forClass(String.class);
    verify(customerClient).releasePoints(eq(1L), eq(2L), eq(7L), eq(2000L), eq(9L), releaseKey.capture());
    assertEquals(redeemKey.getValue().replace(":POINT", "") + ":RELEASE:POINT", releaseKey.getValue());
    // 现金分腿尚未落库（HOLD 阶段就失败），失败快照标记补偿成功
    verify(paymentIntentRepository, never()).insert(any(PaymentIntent.class));
    ArgumentCaptor<PayCollect> failed = ArgumentCaptor.forClass(PayCollect.class);
    verify(payCollectRepository).updateStateAndSnapshot(failed.capture());
    assertEquals("FAILED", failed.getValue().state());
    assertTrue(failed.getValue().responseJson().contains("\"compensated\":true"),
        failed.getValue().responseJson());
    verify(paymentTransactionRepository, never()).insert(any(PaymentTransaction.class));
  }

  @Test
  void collect_cashLegFailureRollsBackWholeTransactionAndNeverLeavesConfirmed() {
    OrderReceivable bill = bill(8000, 0, "WAITING_SETTLEMENT");
    when(orderBillingRepository.findForUpdate(1L, 9L)).thenReturn(Optional.of(bill));
    when(payCollectRepository.findByIdempotencyKey(any(), any())).thenReturn(Optional.empty());
    // 现金分腿已写、订单落账 CAS 冲突（并发收款抢占）：整个事务必须回滚。
    when(orderBillingRepository.markPaid(anyLong(), anyLong(), any(), any(), any())).thenReturn(false);

    ApiException ex = assertThrows(ApiException.class, () -> service.collect(1L, 2L, 9L, 7L, "CNY", 8000L,
        List.of(new CollectApplicationService.PaymentItem("CASH", 8000L)), "key"));

    assertEquals(409, ex.getStatus());
    assertEquals("PAYMENT_ORDER_CONFLICT", ex.getCode());
    verify(paymentIntentRepository).insert(any(PaymentIntent.class));
    verify(transactionManager).rollback(isNull());
    ArgumentCaptor<PayCollect> captured = ArgumentCaptor.forClass(PayCollect.class);
    verify(payCollectRepository).updateStateAndSnapshot(captured.capture());
    assertEquals("FAILED", captured.getValue().state());
    assertTrue(captured.getValue().responseJson().contains("\"compensated\":true"),
        captured.getValue().responseJson());
  }

  @Test
  void collect_sameKeyRetryAfterFailureRerunsWholeFlowWithFreshLegKeys() {
    when(payCollectRepository.findByIdempotencyKey(any(), any())).thenReturn(Optional.empty());
    doThrow(new ApiException(422, "LEDGER_INSUFFICIENT", "储值余额不足"))
        .when(customerClient).deductWallet(any(), any(), any(), any(), any(), any(), any());
    List<CollectApplicationService.PaymentItem> payments = List.of(
        new CollectApplicationService.PaymentItem("POINT", 2000L),
        new CollectApplicationService.PaymentItem("WALLET", 3000L),
        new CollectApplicationService.PaymentItem("CASH", 3000L));
    assertThrows(ApiException.class,
        () -> service.collect(1L, 2L, 9L, 7L, "CNY", 8000L, payments, "key"));

    ArgumentCaptor<PayCollect> record = ArgumentCaptor.forClass(PayCollect.class);
    verify(payCollectRepository).updateStateAndSnapshot(record.capture());
    PayCollect failed = record.getValue();
    assertEquals("FAILED", failed.state());

    // 同键重跑：命中 FAILED（已完整补偿）→ 立即接管并重跑全流程；储值已恢复正常。
    when(payCollectRepository.findByIdempotencyKey(any(), any())).thenReturn(Optional.of(failed));
    when(payCollectRepository.claimFailedAsInit(anyLong())).thenReturn(true);
    doReturn(new CustomerClient.WalletDeductResponse(1L, 7L, 0L, 0L, "CNY"))
        .when(customerClient).deductWallet(any(), any(), any(), any(), any(), any(), any());

    CollectApplicationService.CollectResult result =
        service.collect(1L, 2L, 9L, 7L, "CNY", 8000L, payments, "key");

    assertEquals(0L, result.remainingAmount());
    assertEquals(3, result.collectedByMethod().size());
    ArgumentCaptor<String> redeemKeys = ArgumentCaptor.forClass(String.class);
    verify(customerClient, times(2)).redeemPoints(eq(1L), eq(2L), eq(7L), eq(2000L), eq(9L), redeemKeys.capture());
    assertNotEquals(redeemKeys.getAllValues().get(0), redeemKeys.getAllValues().get(1),
        "重跑必须使用新的分腿幂等键，避免 state=customer 侧幂等键已存在");
    verify(payCollectRepository, times(1)).insert(any(PayCollect.class));
    ArgumentCaptor<PayCollect> updates = ArgumentCaptor.forClass(PayCollect.class);
    verify(payCollectRepository, times(2)).updateStateAndSnapshot(updates.capture());
    assertEquals("CONFIRMED", updates.getValue().state());
  }

  @Test
  void collect_sameKeyRetryReplaysPendingReleaseBeforeRerun() {
    when(payCollectRepository.findByIdempotencyKey(any(), any())).thenReturn(Optional.empty());
    doThrow(new ApiException(422, "LEDGER_INSUFFICIENT", "储值余额不足"))
        .when(customerClient).deductWallet(any(), any(), any(), any(), any(), any(), any());
    doThrow(new IllegalStateException("customer 服务不可用"))
        .when(customerClient).releasePoints(any(), any(), any(), any(), any(), any());
    List<CollectApplicationService.PaymentItem> firstPayments = List.of(
        new CollectApplicationService.PaymentItem("POINT", 2000L),
        new CollectApplicationService.PaymentItem("WALLET", 3000L),
        new CollectApplicationService.PaymentItem("CASH", 3000L));
    assertThrows(ApiException.class,
        () -> service.collect(1L, 2L, 9L, 7L, "CNY", 8000L, firstPayments, "key"));

    ArgumentCaptor<PayCollect> record = ArgumentCaptor.forClass(PayCollect.class);
    verify(payCollectRepository).updateStateAndSnapshot(record.capture());
    PayCollect failed = record.getValue();
    assertTrue(failed.responseJson().contains("\"compensated\":false"), failed.responseJson());

    ArgumentCaptor<String> releaseKeys = ArgumentCaptor.forClass(String.class);
    verify(customerClient).releasePoints(any(), any(), any(), any(), any(), releaseKeys.capture());

    // 同键重跑：补偿未落地 → 先按原键重放归还（customer 侧幂等），成功后再重跑全流程。
    when(payCollectRepository.findByIdempotencyKey(any(), any())).thenReturn(Optional.of(failed));
    when(payCollectRepository.claimFailedAsInit(anyLong())).thenReturn(true);
    doReturn(new CustomerClient.PointReleaseResponse(1L, 7L, 2000L, 0L))
        .when(customerClient).releasePoints(any(), any(), any(), any(), any(), any());
    List<CollectApplicationService.PaymentItem> retryPayments = List.of(
        new CollectApplicationService.PaymentItem("POINT", 2000L),
        new CollectApplicationService.PaymentItem("CASH", 6000L));

    CollectApplicationService.CollectResult result =
        service.collect(1L, 2L, 9L, 7L, "CNY", 8000L, retryPayments, "key");

    assertEquals(0L, result.remainingAmount());
    verify(customerClient, times(2)).releasePoints(any(), any(), any(), any(), any(), releaseKeys.capture());
    assertEquals(releaseKeys.getAllValues().get(0), releaseKeys.getAllValues().get(1),
        "续做补偿必须复用原释放幂等键，重复调用不会重复归还");
  }

  @Test
  void collect_refusesRerunWhenLegacyFailureSnapshotHasNoCompensationInfo() {
    PayCollect legacy = collect(11L, "PC-LEGACY", PayCollect.STATE_FAILED, "{\"status\":422,\"code\":\"PAYMENT_INCOMPLETE\",\"message\":\"收款金额必须足额覆盖服务端应收\"}");
    when(payCollectRepository.findByIdempotencyKey(any(), any())).thenReturn(Optional.of(legacy));

    ApiException ex = assertThrows(ApiException.class,
        () -> service.collect(1L, 2L, 9L, 7L, "CNY", 8000L,
            List.of(new CollectApplicationService.PaymentItem("CASH", 8000L)), "key"));

    assertEquals(409, ex.getStatus());
    assertEquals("PAYMENT_RECONCILIATION_REQUIRED", ex.getCode());
    assertNoPersistenceAndNoDownstreamCall();
    verify(payCollectRepository, never()).claimFailedAsInit(anyLong());
  }

  @Test
  void collect_requiresIdempotencyKey() {
    ApiException ex = assertThrows(ApiException.class,
        () -> service.collect(1L, 2L, 9L, 7L, "CNY", 5000L, List.of(), null));

    assertEquals(400, ex.getStatus());
    assertEquals("IDEMPOTENCY_KEY_REQUIRED", ex.getCode());
  }

  @Test
  void collect_rejectsNegativePayable() {
    ApiException ex = assertThrows(ApiException.class,
        () -> service.collect(1L, 2L, 9L, 7L, "CNY", -1L, List.of(), "key"));

    assertEquals(400, ex.getStatus());
    assertEquals("AMOUNT_INVALID", ex.getCode());
  }

  // ---------------------------------------------------------------------------------------------
  // 领域留痕：/business/** 组合收款没有 BFF 兜底，成功与失败都必须由本服务留痕。
  // ---------------------------------------------------------------------------------------------

  /** 收款成功：SUCCEEDED + 动作码 payment.collect，且失败字段为空。 */
  @Test
  void collect_writesSucceededAudit() {
    when(payCollectRepository.findByIdempotencyKey(any(), any())).thenReturn(Optional.empty());
    when(payCollectRepository.updateStateAndSnapshot(any(PayCollect.class))).thenReturn(true);
    when(paymentIntentRepository.insert(any(PaymentIntent.class))).thenAnswer(inv -> {
      PaymentIntent po = inv.getArgument(0);
      return new PaymentIntent(31L, po.tenantId(), po.storeId(), po.orderId(), po.merchantAccountId(),
          po.provider(), po.paymentMethod(), po.amount(), po.currencyCode(), po.status(),
          po.idempotencyKey(), po.expiresAt(), po.createdAt(), po.updatedAt());
    });

    service.collect(1L, 2L, 9L, 7L, "CNY", 5000L,
        List.of(new CollectApplicationService.PaymentItem("CASH", 5000L)), "key");

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment.collect", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
    assertEquals(null, record.errorCode());
  }

  /** 收款在跨服务分腿阶段失败：FAILURE + ApiException 稳定业务码，且不覆盖成功路径的幂等键。 */
  @Test
  void collect_writesFailureAuditWithStableErrorCode() {
    when(payCollectRepository.findByIdempotencyKey(any(), any())).thenReturn(Optional.empty());
    when(payCollectRepository.updateStateAndSnapshot(any(PayCollect.class))).thenReturn(true);
    doThrow(new ApiException(422, "LEDGER_INSUFFICIENT", "储值余额不足"))
        .when(customerClient).deductWallet(any(), any(), any(), any(), any(), any(), any());

    ApiException ex = assertThrows(ApiException.class, () -> service.collect(1L, 2L, 9L, 7L, "CNY", 8000L,
        List.of(new CollectApplicationService.PaymentItem("WALLET", 8000L)), "key"));

    assertEquals("LEDGER_INSUFFICIENT", ex.getCode());
    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment.collect", record.action());
    assertEquals("组合收款", record.actionLabel());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("LEDGER_INSUFFICIENT", record.errorCode());
    assertEquals("collect", record.resourceType());
    // 失败留痕不带幂等键：同一动作重复失败必须各自留痕，也不得与成功路径的稳定键互相覆盖。
    assertEquals(null, record.idempotencyKey());
    // 失败详情不含金额/支付方式明细/会员信息。
    assertEquals(false, record.detailJson().contains("8000"), record.detailJson());
    assertEquals(false, record.detailJson().contains("WALLET"), record.detailJson());
  }

  /** 平台/框架级失败（非 ApiException）：errorCode 退化为异常类名，仍要留痕。 */
  @Test
  void collect_writesFailureAuditWithExceptionClassNameForUnexpectedFailure() {
    when(payCollectRepository.findByIdempotencyKey(any(), any())).thenReturn(Optional.empty());
    when(payCollectRepository.updateStateAndSnapshot(any(PayCollect.class))).thenReturn(true);
    doThrow(new IllegalStateException("下游超时"))
        .when(customerClient).redeemPoints(any(), any(), any(), any(), any(), any());

    assertThrows(IllegalStateException.class, () -> service.collect(1L, 2L, 9L, 7L, "CNY", 8000L,
        List.of(new CollectApplicationService.PaymentItem("POINT", 8000L)), "key"));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("IllegalStateException", record.errorCode());
  }

  private AuditClient.AuditRecord capturedAudit() {
    ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
    verify(auditClient).recordAsync(captor.capture());
    return captor.getValue();
  }

  /** 前置校验失败/处理中的请求：不得落任何 pay_collect 行，也不得调用 customer 或写账本。 */
  private void assertNoPersistenceAndNoDownstreamCall() {
    verify(payCollectRepository, never()).insert(any(PayCollect.class));
    verify(payCollectRepository, never()).updateStateAndSnapshot(any(PayCollect.class));
    verify(payCollectRepository, never()).claimFailedAsInit(anyLong());
    verify(customerClient, never()).redeemPoints(any(), any(), any(), any(), any(), any());
    verify(customerClient, never()).deductWallet(any(), any(), any(), any(), any(), any(), any());
    verify(paymentIntentRepository, never()).insert(any(PaymentIntent.class));
    verify(orderBillingRepository, never()).markPaid(anyLong(), anyLong(), any(), any(), any());
  }

  /**
   * 同一幂等键并发提交：落库时撞唯一键 uk_pay_collect_idem，接管查询读到赢家的已确认记录后
   * **直接回放**，不重复落任何账本流水。
   *
   * <p>本用例只固定端口契约：撞唯一键 → 回读 → 回放。真实的并发串行化（唯一键挡住第二个写）
   * 依赖数据库，由 {@code PaymentIntegrationTest} 用真实 H2 覆盖；在这里用真线程模拟只会测到
   * 桩件的调度顺序，而不是被测代码。
   */
  @Test
  void collect_duplicateIdempotencyKeyOnInsertReplaysWinnerResultWithoutSecondLedger() throws Exception {
    CollectApplicationService.CollectResult first = new CollectApplicationService.CollectResult(0L,
        List.of(new CollectApplicationService.CollectedMethod("CASH", 5000L)));
    PayCollect winner = collect(1L, "PC-1", PayCollect.STATE_CONFIRMED,
        new ObjectMapper().writeValueAsString(first));
    // 第一次查（判重）为空 → 进入新建；第二次查（撞唯一键后回读）读到赢家。
    when(payCollectRepository.findByIdempotencyKey(any(), any()))
        .thenReturn(Optional.empty(), Optional.of(winner));
    when(payCollectRepository.insert(any(PayCollect.class)))
        .thenThrow(new DuplicateKeyException("uk_pay_collect_idem"));

    CollectApplicationService.CollectResult result = service.collect(1L, 2L, 9L, 7L, "CNY", 5000L,
        List.of(new CollectApplicationService.PaymentItem("CASH", 5000L)), "key");

    assertEquals(first, result, "撞唯一键后应回放赢家的首次结果");
    // 回放不产生第二份账本流水。
    verify(paymentIntentRepository, never()).insert(any(PaymentIntent.class));
    verify(paymentTransactionRepository, never()).insert(any(PaymentTransaction.class));
  }
}
