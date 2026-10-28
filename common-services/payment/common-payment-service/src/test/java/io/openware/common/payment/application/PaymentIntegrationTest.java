package io.openware.common.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.common.payment.domain.billing.model.OrderReceivable;
import io.openware.common.payment.domain.billing.repository.OrderBillingRepository;
import io.openware.common.payment.infra.client.CustomerClient;
import io.openware.common.payment.infra.mq.EventOutboxRelay;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.MqProducer;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import java.math.BigDecimal;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 支付核心写路径集成测试（真跑 H2 + 真实事务）：组合收款（积分 + 储值 + 现金）。
 * 覆盖：成功路径金额与流水不变；前置校验不落任何行；中途分腿失败无账本残留且 pay_collect 不残留
 * CONFIRMED；现金分腿已落库后订单落账冲突 → 整个事务回滚（F6 回归）；幂等重放返回首次结果；
 * 失败后同键重跑可重新走完整流程。
 * WALLET/POINT 抵扣调用 customer 内部端点（外部依赖 mock 并校验）；CASH 差额在本库真实写
 * pay_intent + pay_transaction；整体幂等记录 pay_collect 真落库。仅 mock MQ/审计/customer 客户端。
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentIntegrationTest {

  private static final long TENANT_ID = 1001L;
  private static final long STORE_ID = 2001L;
  private static final long ORDER_ID = 9001L;
  private static final long CUSTOMER_ID = 7001L;

  @MockitoBean
  private MqProducer mqProducer;

  @MockitoBean
  private MqConsumerFactory mqConsumerFactory;

  @MockitoBean
  private AuditClient auditClient;

  @MockitoBean
  private CustomerClient customerClient;

  @MockitoBean
  private EventOutboxRelay eventOutboxRelay;

  @Autowired
  private CollectApplicationService collectApplicationService;

  @Autowired
  private OrderBillingRepository orderBillingRepository;

  @Autowired
  private DataSource dataSource;

  private JdbcTemplate jdbc;

  @BeforeEach
  void setUpTenant() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 0L, 0));
    jdbc = new JdbcTemplate(dataSource);
  }

  @AfterEach
  void tearDownTenant() {
    TenantContextHolder.clear();
  }

  @Test
  void collect_walletAndCash_persistsCollectIntentTransactionAndPostsWalletLeg() {
    List<CollectApplicationService.PaymentItem> payments = List.of(
        new CollectApplicationService.PaymentItem("WALLET", 3000L),
        new CollectApplicationService.PaymentItem("CASH", 5000L));

    CollectApplicationService.CollectResult result = collectApplicationService.collect(
        TENANT_ID, STORE_ID, ORDER_ID, CUSTOMER_ID, "CNY", 8000L, payments, "pay-it-key");

    assertEquals(0L, result.remainingAmount());
    assertEquals(2, result.collectedByMethod().size());

    // 储值抵扣 → 调用 customer 内部扣减端点（账本落在 customer 域，本服务内为外部依赖）
    ArgumentCaptor<String> walletKey = ArgumentCaptor.forClass(String.class);
    verify(customerClient).deductWallet(eq(TENANT_ID), eq(STORE_ID), eq(CUSTOMER_ID), eq(3000L), eq("CNY"),
        eq(ORDER_ID), walletKey.capture());
    assertTrue(walletKey.getValue().endsWith(":WALLET"), walletKey.getValue());

    // 幂等记录真落库且 CONFIRMED
    List<PayCollectRow> collects = collectRows("pay-it-key");
    assertEquals(1, collects.size());
    assertEquals("CONFIRMED", collects.get(0).state());
    assertEquals(STORE_ID, collects.get(0).storeId());
    assertNotNull(collects.get(0).responseJson());
    // 币种快照与订单币种一致（禁止跨币种，16_CURRENCY_CONVENTIONS §5/§6）
    assertEquals("CNY", collects.get(0).currencyCode());

    // 现金差额真落库：pay_intent + pay_transaction（分腿幂等键由收款单号 + 本次尝试随机段派生）
    List<LedgerRow> intents = payIntents(ORDER_ID);
    assertEquals(1, intents.size());
    assertTrue(intents.get(0).idempotencyKey().startsWith(collects.get(0).collectNo() + "#"),
        intents.get(0).idempotencyKey());
    assertTrue(intents.get(0).idempotencyKey().endsWith(":CASH:1"), intents.get(0).idempotencyKey());
    assertEquals(0, intents.get(0).amount().compareTo(new BigDecimal("5000.00")));
    assertEquals("SUCCEEDED", intents.get(0).status());
    assertEquals("CNY", intents.get(0).currencyCode());

    List<LedgerRow> txs = transactionsOf(intents.get(0).id());
    assertEquals(1, txs.size());
    assertEquals(0, txs.get(0).amount().compareTo(new BigDecimal("5000.00")));
    assertEquals("SUCCEEDED", txs.get(0).status());
    assertEquals(STORE_ID, txs.get(0).storeId());

    OrderReceivable order = requireOrder(ORDER_ID);
    assertEquals("PAID", order.tradeStatus());
    assertEquals(0, order.paidOrZero().compareTo(new BigDecimal("8000")));

    // 收款确认事件必须真的写进 Outbox（与上面那行 CONFIRMED 同事务）。
    // 回归点：@Autowired 曾标在少一个参数的构造器上，eventOutbox 恒为 null，
    // 事件从未落库、下游永远收不到通知，而且不报任何错。
    List<OutboxRow> events = outboxRows("collect", collectRows("pay-it-key").get(0).collectNo());
    assertEquals(1, events.size(), "收款确认事件必须写入 pay_event_outbox");
    assertEquals("PENDING", events.get(0).status());
    assertEquals(0, events.get(0).retryCount());
    assertTrue(events.get(0).payloadJson().contains("\"occurredAt\""),
        "载荷必须能序列化含 Instant 的领域事件（ObjectMapper 未注册 JavaTimeModule 时会在此炸掉）");
  }

  /**
   * 全额收款后订单交易状态是 PAID（不是旧模型的 COMPLETED）：换一个新的幂等键再收一次
   * 必须 422 ORDER_STATUS_INVALID，不能因为「PAID 不在旧终态集合里」而写出 0 元收款单。
   */
  @Test
  void collect_rejectsSecondCollectOnFullyPaidOrder() {
    long orderId = 9106L;
    insertOrder(orderId, 5000L);

    collectApplicationService.collect(TENANT_ID, STORE_ID, orderId, CUSTOMER_ID, "CNY", 5000L,
        List.of(new CollectApplicationService.PaymentItem("CASH", 5000L)), "it-paid-once-key");

    OrderReceivable paid = requireOrder(orderId);
    assertEquals("PAID", paid.tradeStatus());
    assertEquals(0L, paid.remainingPayable());

    ApiException ex = assertThrows(ApiException.class, () -> collectApplicationService.collect(
        TENANT_ID, STORE_ID, orderId, CUSTOMER_ID, "CNY", 0L,
        List.of(new CollectApplicationService.PaymentItem("CASH", 0L)), "it-paid-twice-key"));
    assertEquals(422, ex.getStatus());
    assertEquals("ORDER_STATUS_INVALID", ex.getCode());
    assertEquals(1, collectRows("it-paid-once-key").size());
    assertTrue(collectRows("it-paid-twice-key").isEmpty(), "重复收款不得落 pay_collect");
  }

  @Test
  void collect_pointWalletCash_successKeepsAmountsAndLedgerUnchanged() {
    long orderId = 9105L;
    insertOrder(orderId, 10000L);
    grantMethod(TENANT_ID, "POINT");
    List<CollectApplicationService.PaymentItem> payments = List.of(
        new CollectApplicationService.PaymentItem("CASH", 5000L),
        new CollectApplicationService.PaymentItem("WALLET", 3000L),
        new CollectApplicationService.PaymentItem("POINT", 2000L));

    CollectApplicationService.CollectResult result = collectApplicationService.collect(
        TENANT_ID, STORE_ID, orderId, CUSTOMER_ID, "CNY", 10000L, payments, "it-success-key");

    assertEquals(0L, result.remainingAmount());
    assertEquals(List.of(
        new CollectApplicationService.CollectedMethod("POINT", 2000L),
        new CollectApplicationService.CollectedMethod("WALLET", 3000L),
        new CollectApplicationService.CollectedMethod("CASH", 5000L)), result.collectedByMethod());

    ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
    verify(customerClient).redeemPoints(eq(TENANT_ID), eq(STORE_ID), eq(CUSTOMER_ID), eq(2000L), eq(orderId),
        keys.capture());
    verify(customerClient).deductWallet(eq(TENANT_ID), eq(STORE_ID), eq(CUSTOMER_ID), eq(3000L), eq("CNY"),
        eq(orderId), keys.capture());
    assertTrue(keys.getAllValues().get(0).endsWith(":POINT"), keys.getAllValues().get(0));
    assertTrue(keys.getAllValues().get(1).endsWith(":WALLET"), keys.getAllValues().get(1));

    List<LedgerRow> intents = payIntents(orderId);
    assertEquals(1, intents.size());
    assertEquals(0, intents.get(0).amount().compareTo(new BigDecimal("5000")));
    List<LedgerRow> txs = transactionsOf(intents.get(0).id());
    assertEquals(1, txs.size());
    assertEquals(0, txs.get(0).amount().compareTo(new BigDecimal("5000")));

    OrderReceivable order = requireOrder(orderId);
    assertEquals("PAID", order.tradeStatus());
    assertEquals(0, order.paidOrZero().compareTo(new BigDecimal("10000")));
    assertEquals("CONFIRMED", collectRows("it-success-key").get(0).state());
  }

  @Test
  void collect_legSumBelowPayable_leavesNoCollectIntentTransactionOrOrderChange() {
    long orderId = 9101L;
    insertOrder(orderId, 8000L);

    ApiException ex = assertThrows(ApiException.class, () -> collectApplicationService.collect(
        TENANT_ID, STORE_ID, orderId, CUSTOMER_ID, "CNY", 8000L,
        List.of(new CollectApplicationService.PaymentItem("CASH", 7000L)), "it-sum-key"));

    assertEquals(422, ex.getStatus());
    assertEquals("PAYMENT_INCOMPLETE", ex.getCode());
    assertNoResidue(orderId, "it-sum-key");
  }

  @Test
  void collect_legSumAbovePayable_leavesNoCollectIntentTransactionOrOrderChange() {
    long orderId = 9102L;
    insertOrder(orderId, 8000L);

    ApiException ex = assertThrows(ApiException.class, () -> collectApplicationService.collect(
        TENANT_ID, STORE_ID, orderId, CUSTOMER_ID, "CNY", 8000L,
        List.of(new CollectApplicationService.PaymentItem("CASH", 9000L)), "it-over-key"));

    assertEquals(422, ex.getStatus());
    assertEquals("PAYMENT_AMOUNT_MISMATCH", ex.getCode());
    assertNoResidue(orderId, "it-over-key");
  }

  @Test
  void collect_midLegInsufficient_compensatesHeldLegsAndLeavesNoLedgerResidue() {
    long orderId = 9103L;
    insertOrder(orderId, 8000L);
    grantMethod(TENANT_ID, "POINT");
    doThrow(new ApiException(422, "LEDGER_INSUFFICIENT", "储值余额不足"))
        .when(customerClient).deductWallet(any(), any(), any(), any(), any(), any(), any());
    List<CollectApplicationService.PaymentItem> payments = List.of(
        new CollectApplicationService.PaymentItem("POINT", 2000L),
        new CollectApplicationService.PaymentItem("WALLET", 3000L),
        new CollectApplicationService.PaymentItem("CASH", 3000L));

    ApiException ex = assertThrows(ApiException.class, () -> collectApplicationService.collect(
        TENANT_ID, STORE_ID, orderId, CUSTOMER_ID, "CNY", 8000L, payments, "it-midleg-key"));

    assertEquals("LEDGER_INSUFFICIENT", ex.getCode());
    // 已扣减的积分按本次尝试的键归还（跨服务补偿）
    verify(customerClient).releasePoints(eq(TENANT_ID), eq(STORE_ID), eq(CUSTOMER_ID), eq(2000L), eq(orderId),
        any());
    // 本库无任何资金流水残留，pay_collect 只留 FAILED（不残留 SUCCEEDED/CONFIRMED）
    assertNoLedger(orderId);
    List<PayCollectRow> collects = collectRows("it-midleg-key");
    assertEquals(1, collects.size());
    assertEquals("FAILED", collects.get(0).state());
    assertTrue(collects.get(0).responseJson().contains("\"compensated\":true"),
        collects.get(0).responseJson());
    OrderReceivable order = requireOrder(orderId);
    assertEquals("PENDING_PAYMENT", order.tradeStatus());
    assertEquals(0, order.paidOrZero().compareTo(BigDecimal.ZERO));
  }

  @Test
  void collect_walletInsufficient_leavesNoLedgerResidue() {
    long orderId = 9107L;
    insertOrder(orderId, 8000L);
    doThrow(new ApiException(422, "LEDGER_INSUFFICIENT", "储值余额不足"))
        .when(customerClient).deductWallet(any(), any(), any(), any(), any(), any(), any());

    ApiException ex = assertThrows(ApiException.class, () -> collectApplicationService.collect(
        TENANT_ID, STORE_ID, orderId, CUSTOMER_ID, "CNY", 8000L,
        List.of(new CollectApplicationService.PaymentItem("WALLET", 3000L),
            new CollectApplicationService.PaymentItem("CASH", 5000L)), "it-wallet-key"));

    assertEquals("LEDGER_INSUFFICIENT", ex.getCode());
    assertNoLedger(orderId);
    assertEquals("FAILED", collectRows("it-wallet-key").get(0).state());
  }

  /**
   * F6 回归：现金分腿已真实写入 pay_intent 之后，订单落账 CAS 被并发收款抢占（paid_amount 已变），
   * 整个本地事务必须回滚 —— 现金分腿不得残留，pay_collect 不得残留 CONFIRMED。
   */
  @Test
  void collect_cashLegWrittenThenOrderConflict_rollsBackLedgerAndCollectState() {
    long orderId = 9104L;
    insertOrder(orderId, 8000L);
    when(customerClient.deductWallet(any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
      jdbc.update("UPDATE ord_order SET paid_amount = 1000 WHERE id = ? AND tenant_id = ?", orderId, TENANT_ID);
      return new CustomerClient.WalletDeductResponse(1L, CUSTOMER_ID, 0L, 0L, "CNY");
    });

    ApiException ex = assertThrows(ApiException.class, () -> collectApplicationService.collect(
        TENANT_ID, STORE_ID, orderId, CUSTOMER_ID, "CNY", 8000L,
        List.of(new CollectApplicationService.PaymentItem("WALLET", 3000L),
            new CollectApplicationService.PaymentItem("CASH", 5000L)), "it-cashfail-key"));

    assertEquals(409, ex.getStatus());
    assertEquals("PAYMENT_ORDER_CONFLICT", ex.getCode());
    // 现金分腿写过但随事务回滚：无 pay_intent/pay_transaction 残留
    assertNoLedger(orderId);
    assertEquals("FAILED", collectRows("it-cashfail-key").get(0).state());
    verify(customerClient).releaseWallet(eq(TENANT_ID), eq(STORE_ID), eq(CUSTOMER_ID), eq(3000L), eq("CNY"),
        eq(orderId), any());
    OrderReceivable order = requireOrder(orderId);
    assertEquals("PENDING_PAYMENT", order.tradeStatus());
    assertEquals(0, order.paidOrZero().compareTo(new BigDecimal("1000")));
  }

  /**
   * 同一笔收款拆两笔现金类分腿（ALIPAY 3000 + CASH 5000）：生产唯一键 uk_pay_intent_tenant_idem
   * 要求每腿幂等键互不相同（此前统一 `:CASH` 后缀，第二腿直接唯一键冲突使整笔收款回滚）。
   * 本用例真跑 H2（含同名唯一约束）验证两腿都落库、金额与流水一一对应。
   */
  @Test
  void collect_alipayPlusCashSplit_persistsBothLegsWithDistinctKeysAndAmounts() {
    long orderId = 9200L;
    insertOrder(orderId, 8000L);
    grantMethod(TENANT_ID, "ALIPAY");

    CollectApplicationService.CollectResult result = collectApplicationService.collect(
        TENANT_ID, STORE_ID, orderId, CUSTOMER_ID, "CNY", 8000L,
        List.of(new CollectApplicationService.PaymentItem("ALIPAY", 3000L),
            new CollectApplicationService.PaymentItem("CASH", 5000L)), "it-split-key");

    assertEquals(0L, result.remainingAmount());
    assertEquals(List.of(
        new CollectApplicationService.CollectedMethod("ALIPAY", 3000L),
        new CollectApplicationService.CollectedMethod("CASH", 5000L)), result.collectedByMethod());

    List<LedgerRow> intents = payIntentsAllProviders(orderId);
    assertEquals(2, intents.size(), "两笔现金类分腿各写一条 pay_intent");
    LedgerRow alipay = intents.get(0);
    LedgerRow cash = intents.get(1);
    assertEquals("ALIPAY", alipay.provider());
    assertEquals("CASH", cash.provider());
    assertEquals(0, alipay.amount().compareTo(new BigDecimal("3000")));
    assertEquals(0, cash.amount().compareTo(new BigDecimal("5000")));
    assertTrue(alipay.idempotencyKey().endsWith(":CASH:1"), alipay.idempotencyKey());
    assertTrue(cash.idempotencyKey().endsWith(":CASH:2"), cash.idempotencyKey());
    assertNotEquals(alipay.idempotencyKey(), cash.idempotencyKey());
    assertTrue(alipay.idempotencyKey().length() < 64 && cash.idempotencyKey().length() < 64,
        "分腿幂等键必须小于 pay_intent.idempotency_key 的 varchar(64)");
    assertEquals("SUCCEEDED", alipay.status());
    assertEquals("SUCCEEDED", cash.status());

    assertEquals(1, transactionsOf(alipay.id()).size());
    List<LedgerRow> cashTx = transactionsOf(cash.id());
    assertEquals(1, cashTx.size());
    assertEquals(0, cashTx.get(0).amount().compareTo(new BigDecimal("5000")));
    assertEquals("SUCCEEDED", cashTx.get(0).status());

    assertEquals("CONFIRMED", collectRows("it-split-key").get(0).state());
    OrderReceivable order = requireOrder(orderId);
    assertEquals("PAID", order.tradeStatus());
    assertEquals(0, order.paidOrZero().compareTo(new BigDecimal("8000")));
  }

  @Test
  void collect_replaySameIdempotencyKey_returnsFirstResultWithoutSecondLedger() {
    long orderId = 9108L;
    insertOrder(orderId, 8000L);
    List<CollectApplicationService.PaymentItem> payments = List.of(
        new CollectApplicationService.PaymentItem("WALLET", 3000L),
        new CollectApplicationService.PaymentItem("CASH", 5000L));

    CollectApplicationService.CollectResult first = collectApplicationService.collect(
        TENANT_ID, STORE_ID, orderId, CUSTOMER_ID, "CNY", 8000L, payments, "it-replay-key");
    // 首次成功已把订单置为 COMPLETED、服务端应收归零：重放必须命中首次结果，不得再落流水
    CollectApplicationService.CollectResult replay = collectApplicationService.collect(
        TENANT_ID, STORE_ID, orderId, CUSTOMER_ID, "CNY", 8000L, payments, "it-replay-key");

    assertEquals(first, replay);
    verify(customerClient, times(1)).deductWallet(any(), any(), any(), any(), any(), any(), any());
    assertEquals(1, payIntents(orderId).size());
    assertEquals(1, collectRows("it-replay-key").size());
    assertEquals(0, requireOrder(orderId).paidAmount()
        .compareTo(new BigDecimal("8000")));
  }

  @Test
  void collect_sameKeyRetryAfterTransientFailure_completesWholeFlowOnce() {
    long orderId = 9109L;
    insertOrder(orderId, 8000L);
    grantMethod(TENANT_ID, "POINT");
    doThrow(new ApiException(422, "LEDGER_INSUFFICIENT", "储值余额不足"))
        .when(customerClient).deductWallet(any(), any(), any(), any(), any(), any(), any());
    List<CollectApplicationService.PaymentItem> payments = List.of(
        new CollectApplicationService.PaymentItem("POINT", 2000L),
        new CollectApplicationService.PaymentItem("WALLET", 3000L),
        new CollectApplicationService.PaymentItem("CASH", 3000L));
    assertThrows(ApiException.class, () -> collectApplicationService.collect(
        TENANT_ID, STORE_ID, orderId, CUSTOMER_ID, "CNY", 8000L, payments, "it-retry-key"));

    // 外部依赖恢复后同键重跑：必须能重新走完整流程（不残留半成品卡死）
    doReturn(new CustomerClient.WalletDeductResponse(1L, CUSTOMER_ID, 0L, 0L, "CNY"))
        .when(customerClient).deductWallet(any(), any(), any(), any(), any(), any(), any());
    CollectApplicationService.CollectResult result = collectApplicationService.collect(
        TENANT_ID, STORE_ID, orderId, CUSTOMER_ID, "CNY", 8000L, payments, "it-retry-key");

    assertEquals(0L, result.remainingAmount());
    assertEquals(3, result.collectedByMethod().size());
    // 重跑使用新的分腿幂等键（不会撞上 customer 侧已消费的键），账本只落一次
    ArgumentCaptor<String> redeemKeys = ArgumentCaptor.forClass(String.class);
    verify(customerClient, times(2)).redeemPoints(any(), any(), any(), any(), any(), redeemKeys.capture());
    assertNotEquals(redeemKeys.getAllValues().get(0), redeemKeys.getAllValues().get(1),
        "重跑必须使用新的分腿幂等键");
    List<LedgerRow> intents = payIntents(orderId);
    assertEquals(1, intents.size());
    assertEquals(0, intents.get(0).amount().compareTo(new BigDecimal("3000")));
    List<LedgerRow> txs = transactionsOf(intents.get(0).id());
    assertEquals(1, txs.size());
    List<PayCollectRow> collects = collectRows("it-retry-key");
    assertEquals(1, collects.size());
    assertEquals("CONFIRMED", collects.get(0).state());
    OrderReceivable order = requireOrder(orderId);
    assertEquals("PAID", order.tradeStatus());
    assertEquals(0, order.paidOrZero().compareTo(new BigDecimal("8000")));
  }

  /**
   * 跨币种收款必须被拒（16_CURRENCY_CONVENTIONS §5/§6「禁止跨币种交易」）：
   * 订单币种快照是 CNY，却用 USD 收款 → 422 CURRENCY_MISMATCH，且不落任何 pay_collect/pay_intent 行。
   */
  @Test
  void collect_currencyMismatchWithOrderCurrency_rejectsWithoutResidue() {
    long orderId = 9210L;
    insertOrder(orderId, 8000L);

    ApiException ex = assertThrows(ApiException.class, () -> collectApplicationService.collect(
        TENANT_ID, STORE_ID, orderId, CUSTOMER_ID, "USD", 8000L,
        List.of(new CollectApplicationService.PaymentItem("CASH", 8000L)), "it-currency-mismatch-key"));

    assertEquals(422, ex.getStatus());
    assertEquals("CURRENCY_MISMATCH", ex.getCode());
    assertNoResidue(orderId, "it-currency-mismatch-key");
  }

  /**
   * 请求未带币种时按订单币种快照补齐（§5「空值按当时租户币种补齐」）：
   * pay_intent / pay_collect 落库币种必须等于订单币种，不允许出现空币种或默认币种。
   */
  @Test
  void collect_blankCurrency_inheritsOrderCurrencySnapshot() {
    long orderId = 9211L;
    insertOrder(orderId, 8000L);

    collectApplicationService.collect(TENANT_ID, STORE_ID, orderId, CUSTOMER_ID, null, 8000L,
        List.of(new CollectApplicationService.PaymentItem("CASH", 8000L)), "it-blank-currency-key");

    assertEquals("CNY", payIntents(orderId).get(0).currencyCode());
    assertEquals("CNY", collectRows("it-blank-currency-key").get(0).currencyCode());
  }

  /**
   * 落库断言一律走原生 SQL，而不是生产端口：
   * 本测试要验证的正是"写入端真的把列写对了"，经生产读路径自证会让读写的同一个错误互相掩盖。
   */
  private List<PayCollectRow> collectRows(String idempotencyKey) {    return jdbc.query("SELECT id, collect_no, state, store_id, currency_code, response_json"
            + " FROM pay_collect WHERE tenant_id = ? AND idempotency_key = ?",
        (rs, i) -> new PayCollectRow(rs.getLong("id"), rs.getString("collect_no"), rs.getString("state"),
            rs.getLong("store_id"), rs.getString("currency_code"), rs.getString("response_json")),
        TENANT_ID, idempotencyKey);
  }

  private List<LedgerRow> payIntents(long orderId) {
    return ledger("SELECT id, provider, amount, currency_code, status, idempotency_key, store_id"
        + " FROM pay_intent WHERE tenant_id = ? AND order_id = ? AND provider = 'CASH'", orderId);
  }

  private List<LedgerRow> payIntentsAllProviders(long orderId) {
    return ledger("SELECT id, provider, amount, currency_code, status, idempotency_key, store_id"
        + " FROM pay_intent WHERE tenant_id = ? AND order_id = ? ORDER BY id", orderId);
  }

  private List<LedgerRow> transactionsOf(long paymentIntentId) {
    // pay_transaction 没有 idempotency_key 列（幂等键在 pay_intent 上）。
    return jdbc.query("SELECT id, provider, amount, currency_code, status, store_id"
            + " FROM pay_transaction WHERE payment_intent_id = ?",
        (rs, i) -> new LedgerRow(rs.getLong("id"), rs.getString("provider"), rs.getBigDecimal("amount"),
            rs.getString("currency_code"), rs.getString("status"), null, rs.getLong("store_id")),
        paymentIntentId);
  }

  private List<LedgerRow> ledger(String sql, long orderId) {
    return jdbc.query(sql,
        (rs, i) -> new LedgerRow(rs.getLong("id"), rs.getString("provider"), rs.getBigDecimal("amount"),
            rs.getString("currency_code"), rs.getString("status"), rs.getString("idempotency_key"),
            rs.getLong("store_id")),
        TENANT_ID, orderId);
  }

  /** 前置校验失败的请求：不得有任何 pay_collect/pay_intent/pay_transaction 行，订单也不变。 */
  private void assertNoResidue(long orderId, String idempotencyKey) {
    assertEquals(0, collectRows(idempotencyKey).size());
    assertNoLedger(orderId);
    OrderReceivable order = requireOrder(orderId);
    assertEquals("PENDING_PAYMENT", order.tradeStatus());
    assertEquals(0, order.paidOrZero().compareTo(BigDecimal.ZERO));
  }

  /** 本订单不得有任何资金流水（pay_intent 及其 pay_transaction）。 */
  private void assertNoLedger(long orderId) {
    assertEquals(0, payIntentsAllProviders(orderId).size(), "不应有任何 pay_intent 残留");
    Integer txCount = jdbc.queryForObject(
        "SELECT COUNT(*) FROM pay_transaction WHERE payment_intent_id IN"
            + " (SELECT id FROM pay_intent WHERE order_id = ?)",
        Integer.class, orderId);
    assertEquals(0, txCount == null ? 0 : txCount, "本订单不应有任何 pay_transaction 残留");
  }

  /**
   * 经生产端口读订单：{@code findForUpdate} 带行锁，且在测试事务内可见本用例刚写入的行。
   */
  private OrderReceivable requireOrder(long orderId) {
    return orderBillingRepository.findForUpdate(TENANT_ID, orderId)
        .orElseThrow(() -> new AssertionError("订单不存在: " + orderId));
  }

  /** pay_event_outbox 的落库断言视图。 */
  private List<OutboxRow> outboxRows(String aggregateType, String aggregateId) {
    return jdbc.query("SELECT event_id, event_type, aggregate_type, aggregate_id, payload_json,"
            + " status, retry_count FROM pay_event_outbox"
            + " WHERE tenant_id = ? AND aggregate_type = ? AND aggregate_id = ?",
        (rs, i) -> new OutboxRow(rs.getString("event_id"), rs.getString("event_type"),
            rs.getString("aggregate_type"), rs.getString("aggregate_id"), rs.getString("payload_json"),
            rs.getString("status"), rs.getInt("retry_count")),
        TENANT_ID, aggregateType, aggregateId);
  }

  /** pay_intent / pay_transaction 的落库断言视图。 */
  private record LedgerRow(long id, String provider, BigDecimal amount, String currencyCode, String status,
                           String idempotencyKey, long storeId) {}

  /** pay_event_outbox 的落库断言视图。 */
  private record OutboxRow(String eventId, String eventType, String aggregateType, String aggregateId,
                           String payloadJson, String status, int retryCount) {}

  /** pay_collect 的落库断言视图。 */
  private record PayCollectRow(long id, String collectNo, String state, long storeId, String currencyCode,
                               String responseJson) {}

  private void insertOrder(long orderId, long totalAmount) {
    jdbc.update("INSERT INTO ord_order (id, tenant_id, trade_status, currency_code, total_amount, paid_amount,"
            + " refundable_amount, discount_amount, tax_amount, updated_at)"
            + " VALUES (?, ?, 'PENDING_PAYMENT', 'CNY', ?, 0, 0, 0, 0, CURRENT_TIMESTAMP)",
        orderId, TENANT_ID, new BigDecimal(totalAmount));
  }

  private void grantMethod(long tenantId, String method) {
    Integer existing = jdbc.queryForObject(
        "SELECT COUNT(*) FROM tnt_payment_method WHERE tenant_id = ? AND method = ?",
        Integer.class, tenantId, method);
    if (existing != null && existing > 0) {
      return;
    }
    jdbc.update("INSERT INTO tnt_payment_method (tenant_id, method, granted, user_enabled, status,"
            + " created_at, updated_at) VALUES (?, ?, 1, 1, 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
        tenantId, method);
  }
}
