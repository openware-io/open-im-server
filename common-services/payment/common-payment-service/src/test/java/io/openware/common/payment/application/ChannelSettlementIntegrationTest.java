package io.openware.common.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.common.exception.ApiException;
import io.openware.common.payment.infra.mq.EventOutboxRelay;
import io.openware.common.payment.infra.client.CustomerClient;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.MqProducer;
import java.math.BigDecimal;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 渠道回调结算桥集成测试（真跑 H2 + 真实事务）：覆盖二期收口要求的最小用例集——
 * 成功回调原子落 pay_intent + pay_transaction + ord_order 收款、重复回调回放、金额/币种/跨 scope 拒绝、
 * 失败/关闭回调不改订单、退款回调回冲且幂等。
 *
 * <p>断言一律走原生 SQL：本测试要证明的就是"写入端把列写对了"，经生产读路径自证会让读写同一个错误互相掩盖。
 */
@SpringBootTest
@ActiveProfiles("test")
class ChannelSettlementIntegrationTest {

  private static final long TENANT_ID = 1001L;
  private static final long STORE_ID = 2001L;

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
  private ChannelSettlementApplicationService settlementService;

  @Autowired
  private DataSource dataSource;

  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
  }

  @Test
  void settlePayment_success_persistsIntentTransactionOrderAndSettlement() {
    long orderId = 9301L;
    insertOrder(orderId, 8000L);

    ChannelSettlementResult result = settlementService.settle(command("alipay", "ALI-TX-9301",
        "notify-9301", orderId, 8000L, "CNY", "SUCCEEDED", null));

    assertTrue(result.applied());
    assertFalse(result.replayed());
    assertEquals("PAYMENT", result.settlementType());
    assertNotNull(result.payIntentId());
    assertNotNull(result.payTransactionId());

    List<LedgerRow> intents = payIntents(orderId);
    assertEquals(1, intents.size());
    LedgerRow intent = intents.get(0);
    assertEquals("ALIPAY", intent.provider());
    assertEquals(0, intent.amount().compareTo(new BigDecimal("8000")));
    assertEquals("SUCCEEDED", intent.status());
    assertEquals("CNY", intent.currencyCode());
    assertEquals(STORE_ID, intent.storeId());
    assertTrue(intent.idempotencyKey().startsWith("CH:ALIPAY:"), intent.idempotencyKey());
    assertTrue(intent.idempotencyKey().length() <= 64, intent.idempotencyKey());

    List<TransactionRow> transactions = transactionsOf(intent.id());
    assertEquals(1, transactions.size());
    assertEquals("ALI-TX-9301", transactions.get(0).providerTransactionNo());
    assertEquals("SUCCEEDED", transactions.get(0).status());
    assertEquals(0, transactions.get(0).amount().compareTo(new BigDecimal("8000")));

    OrderRow order = order(orderId);
    assertEquals("PAID", order.tradeStatus());
    assertEquals(0, order.paidAmount().compareTo(new BigDecimal("8000")));
    assertEquals(0, order.refundableAmount().compareTo(new BigDecimal("8000")));

    List<SettlementRow> settlements = settlements("ALIPAY", "ALI-TX-9301");
    assertEquals(1, settlements.size());
    assertEquals("PAYMENT", settlements.get(0).kind());
    assertEquals("SUCCEEDED", settlements.get(0).status());
    assertEquals("notify-9301", settlements.get(0).eventId());
    assertEquals(intent.id(), settlements.get(0).payIntentId());
  }

  @Test
  void settlePayment_duplicateCallback_replaysSuccessWithoutSecondLedger() {
    long orderId = 9302L;
    insertOrder(orderId, 8000L);
    ChannelSettlementCommand command = command("wechat", "WX-TX-9302", "evt-9302", orderId,
        8000L, "CNY", "SUCCEEDED", null);

    ChannelSettlementResult first = settlementService.settle(command);
    ChannelSettlementResult replay = settlementService.settle(command);

    assertTrue(first.applied());
    assertFalse(replay.applied());
    assertTrue(replay.replayed());
    // 回放必须反映首次成功后的真实订单状态，而不是重新计算
    assertEquals("PAID", replay.tradeStatus());
    assertEquals(0, replay.paidAmount().compareTo(new BigDecimal("8000")));

    assertEquals(1, payIntents(orderId).size(), "重复回调不得重复落 pay_intent");
    assertEquals(1, transactionCount(orderId), "重复回调不得重复落 pay_transaction");
    assertEquals(1, settlements("WECHAT", "WX-TX-9302").size());
    assertEquals(0, order(orderId).paidAmount().compareTo(new BigDecimal("8000")));
  }

  @Test
  void settlePayment_amountMismatch_rejectsWithoutAnyResidue() {
    long orderId = 9303L;
    insertOrder(orderId, 8000L);
    ChannelSettlementCommand command = command("alipay", "ALI-TX-9303", null, orderId,
        7000L, "CNY", "SUCCEEDED", null);

    ApiException error = assertThrows(ApiException.class, () -> settlementService.settle(command));

    assertEquals(422, error.getStatus());
    assertEquals("PAYMENT_AMOUNT_MISMATCH", error.getCode());
    assertNoLedger(orderId);
    assertEquals("PENDING_PAYMENT", order(orderId).tradeStatus());
    assertEquals(0, order(orderId).paidAmount().compareTo(BigDecimal.ZERO));
  }

  @Test
  void settlePayment_crossTenantScope_rejects() {
    long orderId = 9304L;
    insertOrder(orderId, 8000L);
    ChannelSettlementCommand command = new ChannelSettlementCommand("stripe", "ST-TX-9304", null,
        9999L, null, String.valueOf(orderId), 8000L, "CNY", "SUCCEEDED", null);

    ApiException error = assertThrows(ApiException.class, () -> settlementService.settle(command));

    assertEquals(422, error.getStatus());
    assertEquals("CROSS_TENANT_SCOPE", error.getCode());
    assertNoLedger(orderId);
    assertEquals("PENDING_PAYMENT", order(orderId).tradeStatus());
  }

  @Test
  void settlePayment_crossStoreScope_rejects() {
    long orderId = 9305L;
    insertOrder(orderId, 8000L);
    ChannelSettlementCommand command = new ChannelSettlementCommand("alipay", "ALI-TX-9305", null,
        TENANT_ID, 9999L, String.valueOf(orderId), 8000L, "CNY", "SUCCEEDED", null);

    ApiException error = assertThrows(ApiException.class, () -> settlementService.settle(command));

    assertEquals(422, error.getStatus());
    assertEquals("CROSS_STORE_SCOPE", error.getCode());
    assertNoLedger(orderId);
  }

  @Test
  void settlePayment_currencyMismatch_rejects() {
    long orderId = 9306L;
    insertOrder(orderId, 8000L);
    ChannelSettlementCommand command = command("stripe", "ST-TX-9306", null, orderId,
        8000L, "USD", "SUCCEEDED", null);

    ApiException error = assertThrows(ApiException.class, () -> settlementService.settle(command));

    assertEquals(422, error.getStatus());
    assertEquals("CURRENCY_MISMATCH", error.getCode());
    assertNoLedger(orderId);
  }

  @Test
  void settleFailedAndClosedCallbacks_doNotTouchOrderOrLedger() {
    long failedOrderId = 9307L;
    long closedOrderId = 9308L;
    insertOrder(failedOrderId, 8000L);
    insertOrder(closedOrderId, 8000L);

    ChannelSettlementResult failed = settlementService.settle(command("alipay", "ALI-TX-9307", null,
        failedOrderId, 8000L, "CNY", "FAILED", null));
    ChannelSettlementResult closed = settlementService.settle(command("wechat", "WX-TX-9308", null,
        closedOrderId, 8000L, "CNY", "CLOSED", null));

    assertFalse(failed.applied());
    assertFalse(failed.replayed());
    assertEquals("NONE", failed.settlementType());
    assertFalse(closed.applied());
    assertNoLedger(failedOrderId);
    assertNoLedger(closedOrderId);
    assertEquals("PENDING_PAYMENT", order(failedOrderId).tradeStatus());
    assertEquals("PENDING_PAYMENT", order(closedOrderId).tradeStatus());
  }

  @Test
  void settleRefund_updatesOrderAndReplaysIdempotently() {
    long orderId = 9309L;
    insertOrder(orderId, 8000L);
    settlementService.settle(command("wechat", "WX-TX-9309", "evt-pay-9309", orderId,
        8000L, "CNY", "SUCCEEDED", null));

    ChannelSettlementCommand refund = command("wechat", "WX-TX-9309", "evt-refund-9309", orderId,
        8000L, "CNY", "REFUNDED", 3000L);
    ChannelSettlementResult first = settlementService.settle(refund);
    ChannelSettlementResult replay = settlementService.settle(refund);

    assertTrue(first.applied());
    assertEquals("REFUND", first.settlementType());
    assertTrue(replay.replayed());
    assertFalse(replay.applied());

    OrderRow order = order(orderId);
    assertEquals("PARTIAL", order.refundStatus());
    assertEquals(0, order.paidAmount().compareTo(new BigDecimal("5000")));
    assertEquals(0, order.refundableAmount().compareTo(new BigDecimal("5000")));

    List<SettlementRow> settlements = settlements("WECHAT", "WX-TX-9309");
    assertEquals(2, settlements.size(), "收款与退款各一条结算台账，重复退款回放不新增");
    assertEquals("PAYMENT", settlements.get(0).kind());
    assertEquals("REFUND", settlements.get(1).kind());
    assertEquals(0, settlements.get(1).amount().compareTo(new BigDecimal("3000")));
  }

  @Test
  void settleRefund_exceedingPaidAmount_rejects() {
    long orderId = 9310L;
    insertOrder(orderId, 8000L);
    settlementService.settle(command("alipay", "ALI-TX-9310", null, orderId,
        8000L, "CNY", "SUCCEEDED", null));

    ApiException error = assertThrows(ApiException.class, () -> settlementService.settle(
        command("alipay", "ALI-TX-9310", null, orderId, 8000L, "CNY", "REFUNDED", 9000L)));

    assertEquals(422, error.getStatus());
    assertEquals("REFUND_AMOUNT_EXCEEDS_PAID", error.getCode());
    assertEquals(0, order(orderId).paidAmount().compareTo(new BigDecimal("8000")));
  }

  @Test
  void settlePayment_unknownOrderIsRejectedButClosedCallbackIsAcknowledged() {
    ApiException error = assertThrows(ApiException.class, () -> settlementService.settle(
        command("stripe", "ST-TX-9311", null, 99999L, 100L, "USD", "SUCCEEDED", null)));
    assertEquals(404, error.getStatus());
    assertEquals("ORDER_NOT_FOUND", error.getCode());

    ChannelSettlementResult acknowledged = settlementService.settle(
        command("stripe", "ST-TX-9312", null, 99999L, 100L, "USD", "CLOSED", null));
    assertFalse(acknowledged.applied());
    assertEquals("NONE", acknowledged.settlementType());
  }

  private ChannelSettlementCommand command(String provider, String transactionId, String eventId, long orderId,
                                           long amountMinor, String currency, String status, Long refundAmount) {
    return new ChannelSettlementCommand(provider, transactionId, eventId, TENANT_ID, STORE_ID,
        String.valueOf(orderId), amountMinor, currency, status, refundAmount);
  }

  private void insertOrder(long orderId, long totalAmount) {
    jdbc.update("INSERT INTO ord_order (id, tenant_id, store_id, trade_status, refund_status, currency_code,"
            + " total_amount, paid_amount, refundable_amount, discount_amount, tax_amount, updated_at)"
            + " VALUES (?, ?, ?, 'PENDING_PAYMENT', 'NONE', 'CNY', ?, 0, 0, 0, 0, CURRENT_TIMESTAMP)",
        orderId, TENANT_ID, STORE_ID, new BigDecimal(totalAmount));
  }

  private List<LedgerRow> payIntents(long orderId) {
    return jdbc.query("SELECT id, provider, amount, currency_code, status, idempotency_key, store_id"
            + " FROM pay_intent WHERE tenant_id = ? AND order_id = ? ORDER BY id",
        (rs, i) -> new LedgerRow(rs.getLong("id"), rs.getString("provider"), rs.getBigDecimal("amount"),
            rs.getString("currency_code"), rs.getString("status"), rs.getString("idempotency_key"),
            rs.getLong("store_id")),
        TENANT_ID, orderId);
  }

  private int transactionCount(long orderId) {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM pay_transaction WHERE payment_intent_id IN"
        + " (SELECT id FROM pay_intent WHERE tenant_id = ? AND order_id = ?)", Integer.class, TENANT_ID, orderId);
    return count == null ? 0 : count;
  }

  private List<TransactionRow> transactionsOf(long paymentIntentId) {
    return jdbc.query("SELECT id, provider, provider_transaction_no, amount, currency_code, status"
            + " FROM pay_transaction WHERE payment_intent_id = ?",
        (rs, i) -> new TransactionRow(rs.getLong("id"), rs.getString("provider"),
            rs.getString("provider_transaction_no"), rs.getBigDecimal("amount"),
            rs.getString("currency_code"), rs.getString("status")),
        paymentIntentId);
  }

  private List<SettlementRow> settlements(String provider, String transactionId) {
    return jdbc.query("SELECT id, kind, amount, currency_code, status, event_id, pay_intent_id"
            + " FROM pay_channel_settlement WHERE provider = ? AND transaction_id = ? ORDER BY id",
        (rs, i) -> new SettlementRow(rs.getLong("id"), rs.getString("kind"), rs.getBigDecimal("amount"),
            rs.getString("currency_code"), rs.getString("status"), rs.getString("event_id"),
            rs.getObject("pay_intent_id") == null ? null : rs.getLong("pay_intent_id")),
        provider, transactionId);
  }

  private OrderRow order(long orderId) {
    return jdbc.queryForObject("SELECT trade_status, refund_status, paid_amount, refundable_amount, currency_code"
            + " FROM ord_order WHERE tenant_id = ? AND id = ?",
        (rs, i) -> new OrderRow(rs.getString("trade_status"), rs.getString("refund_status"),
            rs.getBigDecimal("paid_amount"), rs.getBigDecimal("refundable_amount"),
            rs.getString("currency_code")),
        TENANT_ID, orderId);
  }

  private void assertNoLedger(long orderId) {
    assertEquals(0, payIntents(orderId).size(), "不应有 pay_intent 残留");
    assertEquals(0, transactionCount(orderId), "不应有 pay_transaction 残留");
  }

  private record LedgerRow(long id, String provider, BigDecimal amount, String currencyCode, String status,
                           String idempotencyKey, long storeId) {}

  private record TransactionRow(long id, String provider, String providerTransactionNo, BigDecimal amount,
                                String currencyCode, String status) {}

  private record SettlementRow(long id, String kind, BigDecimal amount, String currencyCode, String status,
                               String eventId, Long payIntentId) {}

  private record OrderRow(String tradeStatus, String refundStatus, BigDecimal paidAmount,
                          BigDecimal refundableAmount, String currencyCode) {}
}
