package io.openware.common.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.openware.common.exception.ApiException;
import io.openware.common.payment.infra.client.CustomerClient;
import io.openware.common.payment.infra.mq.EventOutboxRelay;
import io.openware.common.payment.infra.persistence.mapper.PayCollectMapper;
import io.openware.common.payment.infra.persistence.mapper.PayIntentMapper;
import io.openware.common.payment.infra.persistence.mapper.PayTransactionMapper;
import io.openware.common.payment.infra.persistence.mapper.OrderBillingMapper;
import io.openware.common.payment.infra.persistence.po.PayCollectPo;
import io.openware.common.payment.infra.persistence.po.PayIntentPo;
import io.openware.common.payment.infra.persistence.po.PayTransactionPo;
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
  private PayCollectMapper payCollectMapper;

  @Autowired
  private PayIntentMapper payIntentMapper;

  @Autowired
  private PayTransactionMapper payTransactionMapper;

  @Autowired
  private OrderBillingMapper orderBillingMapper;

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
    List<PayCollectPo> collects = collectRows("pay-it-key");
    assertEquals(1, collects.size());
    assertEquals("CONFIRMED", collects.get(0).getState());
    assertNotNull(collects.get(0).getResponseJson());
    // 币种快照与订单币种一致（禁止跨币种，16_CURRENCY_CONVENTIONS §5/§6）
    assertEquals("CNY", collects.get(0).getCurrencyCode());

    // 现金差额真落库：pay_intent + pay_transaction（分腿幂等键由收款单号 + 本次尝试随机段派生）
    List<PayIntentPo> intents = payIntentMapper.selectList(
        new QueryWrapper<PayIntentPo>().eq("order_id", ORDER_ID).eq("provider", "CASH"));
    assertEquals(1, intents.size());
    assertTrue(intents.get(0).getIdempotencyKey().startsWith(collects.get(0).getCollectNo() + "#"),
        intents.get(0).getIdempotencyKey());
    assertTrue(intents.get(0).getIdempotencyKey().endsWith(":CASH:1"), intents.get(0).getIdempotencyKey());
    assertEquals(0, intents.get(0).getAmount().compareTo(new BigDecimal("5000.00")));
    assertEquals("SUCCEEDED", intents.get(0).getStatus());
    assertEquals("CNY", intents.get(0).getCurrencyCode());

    List<PayTransactionPo> txs = payTransactionMapper.selectList(
        new QueryWrapper<PayTransactionPo>().eq("payment_intent_id", intents.get(0).getId()));
    assertEquals(1, txs.size());
    assertEquals(0, txs.get(0).getAmount().compareTo(new BigDecimal("5000.00")));
    assertEquals("SUCCEEDED", txs.get(0).getStatus());

    var order = orderBillingMapper.selectForUpdate(TENANT_ID, ORDER_ID);
    assertEquals("COMPLETED", order.getStatus());
    assertEquals(0, order.getPaidAmount().compareTo(new BigDecimal("8000")));
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

    List<PayIntentPo> intents = payIntents(orderId);
    assertEquals(1, intents.size());
    assertEquals(0, intents.get(0).getAmount().compareTo(new BigDecimal("5000")));
    List<PayTransactionPo> txs = payTransactionMapper.selectList(
        new QueryWrapper<PayTransactionPo>().eq("payment_intent_id", intents.get(0).getId()));
    assertEquals(1, txs.size());
    assertEquals(0, txs.get(0).getAmount().compareTo(new BigDecimal("5000")));

    var order = orderBillingMapper.selectForUpdate(TENANT_ID, orderId);
    assertEquals("COMPLETED", order.getStatus());
    assertEquals(0, order.getPaidAmount().compareTo(new BigDecimal("10000")));
    assertEquals("CONFIRMED", collectRows("it-success-key").get(0).getState());
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
    List<PayCollectPo> collects = collectRows("it-midleg-key");
    assertEquals(1, collects.size());
    assertEquals("FAILED", collects.get(0).getState());
    assertTrue(collects.get(0).getResponseJson().contains("\"compensated\":true"),
        collects.get(0).getResponseJson());
    var order = orderBillingMapper.selectForUpdate(TENANT_ID, orderId);
    assertEquals("WAITING_SETTLEMENT", order.getStatus());
    assertEquals(0, order.getPaidAmount().compareTo(BigDecimal.ZERO));
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
    assertEquals("FAILED", collectRows("it-wallet-key").get(0).getState());
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
    assertEquals("FAILED", collectRows("it-cashfail-key").get(0).getState());
    verify(customerClient).releaseWallet(eq(TENANT_ID), eq(STORE_ID), eq(CUSTOMER_ID), eq(3000L), eq("CNY"),
        eq(orderId), any());
    var order = orderBillingMapper.selectForUpdate(TENANT_ID, orderId);
    assertEquals("WAITING_SETTLEMENT", order.getStatus());
    assertEquals(0, order.getPaidAmount().compareTo(new BigDecimal("1000")));
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

    List<PayIntentPo> intents = payIntentMapper.selectList(
        new QueryWrapper<PayIntentPo>().eq("order_id", orderId).orderByAsc("id"));
    assertEquals(2, intents.size(), "两笔现金类分腿各写一条 pay_intent");
    PayIntentPo alipay = intents.get(0);
    PayIntentPo cash = intents.get(1);
    assertEquals("ALIPAY", alipay.getProvider());
    assertEquals("CASH", cash.getProvider());
    assertEquals(0, alipay.getAmount().compareTo(new BigDecimal("3000")));
    assertEquals(0, cash.getAmount().compareTo(new BigDecimal("5000")));
    assertTrue(alipay.getIdempotencyKey().endsWith(":CASH:1"), alipay.getIdempotencyKey());
    assertTrue(cash.getIdempotencyKey().endsWith(":CASH:2"), cash.getIdempotencyKey());
    assertNotEquals(alipay.getIdempotencyKey(), cash.getIdempotencyKey());
    assertTrue(alipay.getIdempotencyKey().length() < 64 && cash.getIdempotencyKey().length() < 64,
        "分腿幂等键必须小于 pay_intent.idempotency_key 的 varchar(64)");
    assertEquals("SUCCEEDED", alipay.getStatus());
    assertEquals("SUCCEEDED", cash.getStatus());

    assertEquals(1, payTransactionMapper.selectList(new QueryWrapper<PayTransactionPo>()
        .eq("payment_intent_id", alipay.getId())).size());
    List<PayTransactionPo> cashTx = payTransactionMapper.selectList(
        new QueryWrapper<PayTransactionPo>().eq("payment_intent_id", cash.getId()));
    assertEquals(1, cashTx.size());
    assertEquals(0, cashTx.get(0).getAmount().compareTo(new BigDecimal("5000")));
    assertEquals("SUCCEEDED", cashTx.get(0).getStatus());

    assertEquals("CONFIRMED", collectRows("it-split-key").get(0).getState());
    var order = orderBillingMapper.selectForUpdate(TENANT_ID, orderId);
    assertEquals("COMPLETED", order.getStatus());
    assertEquals(0, order.getPaidAmount().compareTo(new BigDecimal("8000")));
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
    assertEquals(0, orderBillingMapper.selectForUpdate(TENANT_ID, orderId).getPaidAmount()
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
    List<PayIntentPo> intents = payIntents(orderId);
    assertEquals(1, intents.size());
    assertEquals(0, intents.get(0).getAmount().compareTo(new BigDecimal("3000")));
    List<PayTransactionPo> txs = payTransactionMapper.selectList(
        new QueryWrapper<PayTransactionPo>().eq("payment_intent_id", intents.get(0).getId()));
    assertEquals(1, txs.size());
    List<PayCollectPo> collects = collectRows("it-retry-key");
    assertEquals(1, collects.size());
    assertEquals("CONFIRMED", collects.get(0).getState());
    var order = orderBillingMapper.selectForUpdate(TENANT_ID, orderId);
    assertEquals("COMPLETED", order.getStatus());
    assertEquals(0, order.getPaidAmount().compareTo(new BigDecimal("8000")));
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

    assertEquals("CNY", payIntents(orderId).get(0).getCurrencyCode());
    assertEquals("CNY", collectRows("it-blank-currency-key").get(0).getCurrencyCode());
  }

  private List<PayCollectPo> collectRows(String idempotencyKey) {
    return payCollectMapper.selectList(
        new QueryWrapper<PayCollectPo>().eq("idempotency_key", idempotencyKey));
  }

  private List<PayIntentPo> payIntents(long orderId) {
    return payIntentMapper.selectList(
        new QueryWrapper<PayIntentPo>().eq("order_id", orderId).eq("provider", "CASH"));
  }

  /** 前置校验失败的请求：不得有任何 pay_collect/pay_intent/pay_transaction 行，订单也不变。 */
  private void assertNoResidue(long orderId, String idempotencyKey) {
    assertEquals(0, collectRows(idempotencyKey).size());
    assertNoLedger(orderId);
    var order = orderBillingMapper.selectForUpdate(TENANT_ID, orderId);
    assertEquals("WAITING_SETTLEMENT", order.getStatus());
    assertEquals(0, order.getPaidAmount().compareTo(BigDecimal.ZERO));
  }

  /** 本订单不得有任何资金流水（pay_intent 及其 pay_transaction）。 */
  private void assertNoLedger(long orderId) {
    List<PayIntentPo> intents = payIntentMapper.selectList(
        new QueryWrapper<PayIntentPo>().eq("order_id", orderId));
    assertEquals(0, intents.size(), "不应有任何 pay_intent 残留");
    assertEquals(0, payTransactionMapper.selectCount(new QueryWrapper<PayTransactionPo>()
        .inSql("payment_intent_id", "SELECT id FROM pay_intent WHERE order_id = " + orderId)),
        "本订单不应有任何 pay_transaction 残留");
  }

  private void insertOrder(long orderId, long totalAmount) {
    jdbc.update("INSERT INTO ord_order (id, tenant_id, status, currency_code, total_amount, paid_amount,"
            + " refundable_amount, discount_amount, tax_amount, updated_at)"
            + " VALUES (?, ?, 'WAITING_SETTLEMENT', 'CNY', ?, 0, 0, 0, 0, CURRENT_TIMESTAMP)",
        orderId, TENANT_ID, new BigDecimal(totalAmount));
  }

  private void grantMethod(long tenantId, String method) {
    Integer existing = jdbc.queryForObject(
        "SELECT COUNT(*) FROM tenant_payment_method WHERE tenant_id = ? AND method = ?",
        Integer.class, tenantId, method);
    if (existing != null && existing > 0) {
      return;
    }
    jdbc.update("INSERT INTO tenant_payment_method (tenant_id, method, granted, user_enabled, status,"
            + " created_at, updated_at) VALUES (?, ?, 1, 1, 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
        tenantId, method);
  }
}
