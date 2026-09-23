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
import io.openware.common.payment.infra.persistence.mapper.PayCollectMapper;
import io.openware.common.payment.infra.persistence.mapper.PayIntentMapper;
import io.openware.common.payment.infra.persistence.mapper.PayTransactionMapper;
import io.openware.common.payment.infra.persistence.mapper.OrderBillingMapper;
import io.openware.common.payment.infra.persistence.po.OrderBillingPo;
import io.openware.common.payment.infra.persistence.po.PayCollectPo;
import io.openware.common.payment.infra.persistence.po.PayIntentPo;
import io.openware.common.payment.infra.persistence.po.PayTransactionPo;
import io.openware.infrastructure.audit.AuditClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

  private final PayIntentMapper payIntentMapper = mock(PayIntentMapper.class);
  private final PayTransactionMapper payTransactionMapper = mock(PayTransactionMapper.class);
  private final PayCollectMapper payCollectMapper = mock(PayCollectMapper.class);
  private final CustomerClient customerClient = mock(CustomerClient.class);
  private final PaymentMethodApplicationService paymentMethodService = mock(PaymentMethodApplicationService.class);
  private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
  private final AuditClient auditClient = mock(AuditClient.class);
  private final OrderBillingMapper orderBillingMapper = mock(OrderBillingMapper.class);
  private final CollectApplicationService service = new CollectApplicationService(payIntentMapper,
      payTransactionMapper, payCollectMapper, customerClient, paymentMethodService,
      transactionManager, auditClient, orderBillingMapper);

  @BeforeEach
  void allowPaymentMethods() {
    when(paymentMethodService.isTenantAllowed(anyLong(), any(), any())).thenReturn(true);
    when(orderBillingMapper.selectForUpdate(anyLong(), anyLong())).thenReturn(null);
  }

  @Test
  void collect_rejectsClientPayableWhenItDiffersFromServerBill() {
    OrderBillingPo bill = new OrderBillingPo();
    bill.setStatus("WAITING_SETTLEMENT");
    bill.setTotalAmount(java.math.BigDecimal.valueOf(10000));
    bill.setPaidAmount(java.math.BigDecimal.ZERO);
    when(orderBillingMapper.selectForUpdate(1L, 9L)).thenReturn(bill);
    when(orderBillingMapper.markPaid(anyLong(), anyLong(), any(), any(), any())).thenReturn(1);

    ApiException ex = assertThrows(ApiException.class, () -> service.collect(1L, 2L, 9L, 7L,
        "CNY", 1L, List.of(), "key"));

    assertEquals("PAYMENT_AMOUNT_MISMATCH", ex.getCode());
    verify(payCollectMapper, never()).insert(any(PayCollectPo.class));
  }

  @Test
  void collect_marksOrderCompletedAfterFullPayment() {
    OrderBillingPo bill = new OrderBillingPo();
    bill.setStatus("WAITING_SETTLEMENT");
    bill.setTotalAmount(java.math.BigDecimal.valueOf(5000));
    bill.setPaidAmount(java.math.BigDecimal.ZERO);
    when(orderBillingMapper.selectForUpdate(1L, 9L)).thenReturn(bill);
    when(orderBillingMapper.markPaid(anyLong(), anyLong(), any(), any(), any())).thenReturn(1);

    service.collect(1L, 2L, 9L, 7L, "CNY", 5000L,
        List.of(new CollectApplicationService.PaymentItem("CASH", 5000L)), "key");

    verify(orderBillingMapper).markPaid(1L, 9L, java.math.BigDecimal.ZERO,
        java.math.BigDecimal.valueOf(5000), java.math.BigDecimal.valueOf(5000));
  }

  @Test
  void collect_deductsInOrderPointWalletCash() {
    when(payCollectMapper.selectOne(any())).thenReturn(null);
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
    InOrder order = inOrder(customerClient, payIntentMapper);
    order.verify(customerClient).redeemPoints(eq(1L), eq(2L), eq(7L), eq(2000L), eq(9L), keys.capture());
    order.verify(customerClient).deductWallet(eq(1L), eq(2L), eq(7L), eq(3000L), eq("CNY"), eq(9L), keys.capture());
    order.verify(payIntentMapper).insert(any(PayIntentPo.class));

    // 同一笔收款的三条分腿共用一个「尝试键」前缀，且各自带分腿后缀（不再直接用客户端幂等键，
    // 以便失败重跑时不会撞上 customer 侧已消费的幂等键）。现金类分腿后缀带序号。
    String attempt = keys.getAllValues().get(0).replace(":POINT", "");
    assertEquals(attempt + ":WALLET", keys.getAllValues().get(1));
    ArgumentCaptor<PayIntentPo> intent = ArgumentCaptor.forClass(PayIntentPo.class);
    verify(payIntentMapper).insert(intent.capture());
    assertEquals(attempt + ":CASH:1", intent.getValue().getIdempotencyKey());
  }

  /**
   * 同一笔收款拆两笔现金类分腿（ALIPAY + CASH）：两腿运行结果都进入 collectedByMethod，
   * 且各自生成互不相同的分腿幂等键（此前统一用 `:CASH`，第二腿会撞生产唯一键 uk_pay_intent_tenant_idem）。
   */
  @Test
  void collect_alipayAndCashSplitUsesDistinctIdempotencyKeysPerLeg() {
    when(payCollectMapper.selectOne(any())).thenReturn(null);
    when(payIntentMapper.insert(any(PayIntentPo.class))).thenAnswer(inv -> {
      PayIntentPo po = inv.getArgument(0);
      po.setId(po.getId() == null ? 1L : po.getId() + 1);
      return 1;
    });

    CollectApplicationService.CollectResult result = service.collect(1L, 2L, 9L, 7L, "CNY", 8000L,
        List.of(new CollectApplicationService.PaymentItem("ALIPAY", 3000L),
            new CollectApplicationService.PaymentItem("CASH", 5000L)), "key");

    assertEquals(List.of(
        new CollectApplicationService.CollectedMethod("ALIPAY", 3000L),
        new CollectApplicationService.CollectedMethod("CASH", 5000L)), result.collectedByMethod());

    ArgumentCaptor<PayIntentPo> intents = ArgumentCaptor.forClass(PayIntentPo.class);
    verify(payIntentMapper, times(2)).insert(intents.capture());
    String first = intents.getAllValues().get(0).getIdempotencyKey();
    String second = intents.getAllValues().get(1).getIdempotencyKey();
    assertNotEquals(first, second, "同一笔收款的两笔现金类分腿幂等键必须不同");
    assertTrue(first.endsWith(":CASH:1"), first);
    assertTrue(second.endsWith(":CASH:2"), second);
    assertEquals(first.substring(0, first.length() - 1) + "2", second);
    assertTrue(first.length() < 64 && second.length() < 64, "幂等键长度必须小于 64：" + first + " / " + second);
    verify(payTransactionMapper, times(2)).insert(any(PayTransactionPo.class));
  }

  @Test
  void collect_rejectsLegSumBelowPayableWithoutAnyPersistence() {
    when(payCollectMapper.selectOne(any())).thenReturn(null);
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
    when(payCollectMapper.selectOne(any())).thenReturn(null);
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
    when(payCollectMapper.selectOne(any())).thenReturn(null);
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
    when(payCollectMapper.selectOne(any())).thenReturn(null);
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
    when(payCollectMapper.selectOne(any())).thenReturn(null);
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
    PayCollectPo existing = new PayCollectPo();
    existing.setState("CONFIRMED");
    existing.setResponseJson(new ObjectMapper().writeValueAsString(first));
    when(payCollectMapper.selectOne(any())).thenReturn(existing);

    CollectApplicationService.CollectResult result =
        service.collect(1L, 2L, 9L, 7L, "CNY", 5000L, List.of(), "key");

    assertEquals(0L, result.remainingAmount());
    assertEquals(List.of(new CollectApplicationService.CollectedMethod("CASH", 5000L)),
        result.collectedByMethod());
    verify(payCollectMapper, never()).insert(any(PayCollectPo.class));
    verify(customerClient, never()).redeemPoints(any(), any(), any(), any(), any(), any());
  }

  @Test
  void collect_replaysFirstResultEvenWhenOrderIsAlreadyPaid() throws Exception {
    // 首次成功已把订单置为 COMPLETED、payable 归零；重放必须命中幂等记录，而不是被服务端应收校验拒掉。
    OrderBillingPo bill = new OrderBillingPo();
    bill.setStatus("COMPLETED");
    bill.setTotalAmount(java.math.BigDecimal.valueOf(8000));
    bill.setPaidAmount(java.math.BigDecimal.valueOf(8000));
    when(orderBillingMapper.selectForUpdate(1L, 9L)).thenReturn(bill);
    CollectApplicationService.CollectResult first =
        new CollectApplicationService.CollectResult(0L,
            List.of(new CollectApplicationService.CollectedMethod("CASH", 8000L)));
    PayCollectPo existing = new PayCollectPo();
    existing.setState("CONFIRMED");
    existing.setResponseJson(new ObjectMapper().writeValueAsString(first));
    when(payCollectMapper.selectOne(any())).thenReturn(existing);

    CollectApplicationService.CollectResult result =
        service.collect(1L, 2L, 9L, 7L, "CNY", 8000L, List.of(), "key");

    assertEquals(first, result);
    verify(orderBillingMapper, never()).markPaid(anyLong(), anyLong(), any(), any(), any());
    verify(payIntentMapper, never()).insert(any(PayIntentPo.class));
  }

  @Test
  void collect_throws409WhenSameKeyIsProcessing() {
    PayCollectPo existing = new PayCollectPo();
    existing.setState("HOLD");
    when(payCollectMapper.selectOne(any())).thenReturn(existing);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.collect(1L, 2L, 9L, 7L, "CNY", 5000L, List.of(), "key"));

    assertEquals(409, ex.getStatus());
    assertEquals("PAYMENT_PROCESSING", ex.getCode());
    assertNoPersistenceAndNoDownstreamCall();
  }

  @Test
  void collect_throws422WhenPointExceedsRemaining() {
    when(payCollectMapper.selectOne(any())).thenReturn(null);
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
    when(payCollectMapper.selectOne(any())).thenReturn(null);
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
    when(payCollectMapper.selectOne(any())).thenReturn(null);
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
    verify(payIntentMapper, never()).insert(any(PayIntentPo.class));
    ArgumentCaptor<PayCollectPo> failed = ArgumentCaptor.forClass(PayCollectPo.class);
    verify(payCollectMapper).updateById(failed.capture());
    assertEquals("FAILED", failed.getValue().getState());
    assertTrue(failed.getValue().getResponseJson().contains("\"compensated\":true"),
        failed.getValue().getResponseJson());
    verify(payTransactionMapper, never()).insert(any(PayTransactionPo.class));
  }

  @Test
  void collect_cashLegFailureRollsBackWholeTransactionAndNeverLeavesConfirmed() {
    OrderBillingPo bill = new OrderBillingPo();
    bill.setStatus("WAITING_SETTLEMENT");
    bill.setTotalAmount(java.math.BigDecimal.valueOf(8000));
    bill.setPaidAmount(java.math.BigDecimal.ZERO);
    when(orderBillingMapper.selectForUpdate(1L, 9L)).thenReturn(bill);
    when(payCollectMapper.selectOne(any())).thenReturn(null);
    // 现金分腿已写、订单落账 CAS 冲突（并发收款抢占）：整个事务必须回滚。
    when(orderBillingMapper.markPaid(anyLong(), anyLong(), any(), any(), any())).thenReturn(0);

    ApiException ex = assertThrows(ApiException.class, () -> service.collect(1L, 2L, 9L, 7L, "CNY", 8000L,
        List.of(new CollectApplicationService.PaymentItem("CASH", 8000L)), "key"));

    assertEquals(409, ex.getStatus());
    assertEquals("PAYMENT_ORDER_CONFLICT", ex.getCode());
    verify(payIntentMapper).insert(any(PayIntentPo.class));
    verify(transactionManager).rollback(isNull());
    ArgumentCaptor<PayCollectPo> captured = ArgumentCaptor.forClass(PayCollectPo.class);
    verify(payCollectMapper).updateById(captured.capture());
    assertEquals("FAILED", captured.getValue().getState());
    assertTrue(captured.getValue().getResponseJson().contains("\"compensated\":true"),
        captured.getValue().getResponseJson());
  }

  @Test
  void collect_sameKeyRetryAfterFailureRerunsWholeFlowWithFreshLegKeys() {
    when(payCollectMapper.selectOne(any())).thenReturn(null);
    doThrow(new ApiException(422, "LEDGER_INSUFFICIENT", "储值余额不足"))
        .when(customerClient).deductWallet(any(), any(), any(), any(), any(), any(), any());
    List<CollectApplicationService.PaymentItem> payments = List.of(
        new CollectApplicationService.PaymentItem("POINT", 2000L),
        new CollectApplicationService.PaymentItem("WALLET", 3000L),
        new CollectApplicationService.PaymentItem("CASH", 3000L));
    assertThrows(ApiException.class,
        () -> service.collect(1L, 2L, 9L, 7L, "CNY", 8000L, payments, "key"));

    ArgumentCaptor<PayCollectPo> record = ArgumentCaptor.forClass(PayCollectPo.class);
    verify(payCollectMapper).updateById(record.capture());
    PayCollectPo failed = record.getValue();
    assertEquals("FAILED", failed.getState());

    // 同键重跑：命中 FAILED（已完整补偿）→ 立即接管并重跑全流程；储值已恢复正常。
    when(payCollectMapper.selectOne(any())).thenReturn(failed);
    when(payCollectMapper.update(any(), any())).thenReturn(1);
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
    verify(payCollectMapper, times(1)).insert(any(PayCollectPo.class));
    ArgumentCaptor<PayCollectPo> updates = ArgumentCaptor.forClass(PayCollectPo.class);
    verify(payCollectMapper, times(2)).updateById(updates.capture());
    assertEquals("CONFIRMED", updates.getValue().getState());
  }

  @Test
  void collect_sameKeyRetryReplaysPendingReleaseBeforeRerun() {
    when(payCollectMapper.selectOne(any())).thenReturn(null);
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

    ArgumentCaptor<PayCollectPo> record = ArgumentCaptor.forClass(PayCollectPo.class);
    verify(payCollectMapper).updateById(record.capture());
    PayCollectPo failed = record.getValue();
    assertTrue(failed.getResponseJson().contains("\"compensated\":false"), failed.getResponseJson());

    ArgumentCaptor<String> releaseKeys = ArgumentCaptor.forClass(String.class);
    verify(customerClient).releasePoints(any(), any(), any(), any(), any(), releaseKeys.capture());

    // 同键重跑：补偿未落地 → 先按原键重放归还（customer 侧幂等），成功后再重跑全流程。
    when(payCollectMapper.selectOne(any())).thenReturn(failed);
    when(payCollectMapper.update(any(), any())).thenReturn(1);
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
    PayCollectPo legacy = new PayCollectPo();
    legacy.setId(11L);
    legacy.setCollectNo("PC-LEGACY");
    legacy.setOrderId(9L);
    legacy.setState("FAILED");
    legacy.setResponseJson("{\"status\":422,\"code\":\"PAYMENT_INCOMPLETE\",\"message\":\"收款金额必须足额覆盖服务端应收\"}");
    when(payCollectMapper.selectOne(any())).thenReturn(legacy);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.collect(1L, 2L, 9L, 7L, "CNY", 8000L,
            List.of(new CollectApplicationService.PaymentItem("CASH", 8000L)), "key"));

    assertEquals(409, ex.getStatus());
    assertEquals("PAYMENT_RECONCILIATION_REQUIRED", ex.getCode());
    assertNoPersistenceAndNoDownstreamCall();
    verify(payCollectMapper, never()).update(any(), any());
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
    when(payCollectMapper.selectOne(any())).thenReturn(null);
    when(payCollectMapper.updateById(any(PayCollectPo.class))).thenReturn(1);
    when(payIntentMapper.insert(any(PayIntentPo.class))).thenReturn(1);

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
    when(payCollectMapper.selectOne(any())).thenReturn(null);
    when(payCollectMapper.updateById(any(PayCollectPo.class))).thenReturn(1);
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
    when(payCollectMapper.selectOne(any())).thenReturn(null);
    when(payCollectMapper.updateById(any(PayCollectPo.class))).thenReturn(1);
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
    verify(payCollectMapper, never()).insert(any(PayCollectPo.class));
    verify(payCollectMapper, never()).updateById(any(PayCollectPo.class));
    verify(payCollectMapper, never()).update(any(), any());
    verify(customerClient, never()).redeemPoints(any(), any(), any(), any(), any(), any());
    verify(customerClient, never()).deductWallet(any(), any(), any(), any(), any(), any(), any());
    verify(payIntentMapper, never()).insert(any(PayIntentPo.class));
    verify(orderBillingMapper, never()).markPaid(anyLong(), anyLong(), any(), any(), any());
  }

  @Test
  void collect_concurrentSameIdempotencyKey_createsSingleLedgerAndReturnsSameResult() throws Exception {
    AtomicReference<PayCollectPo> committed = new AtomicReference<>();
    CountDownLatch winnerDone = new CountDownLatch(1);
    // 首查：winner 确认前一律 null，让 100 个请求都进入 insert 竞争。
    when(payCollectMapper.selectOne(any())).thenAnswer(inv ->
        winnerDone.getCount() == 0 ? committed.get() : null);
    // insert：唯一键 uk_pay_collect_idem，仅首个 CAS 成功，其余等 winner 确认后抛 DuplicateKeyException 兜底。
    when(payCollectMapper.insert(any(PayCollectPo.class))).thenAnswer(inv -> {
      PayCollectPo po = inv.getArgument(0);
      if (committed.compareAndSet(null, po)) {
        return 1;
      }
      winnerDone.await(30, TimeUnit.SECONDS);
      throw new DuplicateKeyException("uk_pay_collect_idem");
    });
    // 确认成功后唤醒等待中的并发请求。
    when(payCollectMapper.updateById(any(PayCollectPo.class))).thenAnswer(inv -> {
      PayCollectPo po = inv.getArgument(0);
      if ("CONFIRMED".equals(po.getState())) {
        winnerDone.countDown();
      }
      return 1;
    });

    int threads = 100;
    CyclicBarrier barrier = new CyclicBarrier(threads);
    CountDownLatch done = new CountDownLatch(threads);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<CollectApplicationService.CollectResult> results = Collections.synchronizedList(new ArrayList<>());
    List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());
    try {
      for (int i = 0; i < threads; i++) {
        pool.execute(() -> {
          try {
            barrier.await();
          } catch (InterruptedException | BrokenBarrierException e) {
            unexpected.add(e);
            done.countDown();
            return;
          }
          try {
            CollectApplicationService.CollectResult r = service.collect(
                1L, 2L, 9L, 7L, "CNY", 5000L,
                List.of(new CollectApplicationService.PaymentItem("CASH", 5000L)), "key");
            results.add(r);
          } catch (RuntimeException e) {
            unexpected.add(e);
          } finally {
            done.countDown();
          }
        });
      }
      assertTrue(done.await(30, TimeUnit.SECONDS), "并发收款应在 30s 内完成");
    } finally {
      pool.shutdownNow();
    }

    assertEquals(0, unexpected.size(), "不应有意外异常: " + unexpected);
    assertEquals(threads, results.size(), "同一幂等键并发提交应全部返回同一结果");
    CollectApplicationService.CollectResult first = results.get(0);
    for (CollectApplicationService.CollectResult r : results) {
      assertEquals(first, r);
    }
    // 只产生一次账本流水：pay_intent + pay_transaction 各写一次。
    verify(payIntentMapper, times(1)).insert(any(PayIntentPo.class));
    verify(payTransactionMapper, times(1)).insert(any(PayTransactionPo.class));
  }
}
