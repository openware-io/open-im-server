package io.openware.common.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.openware.common.exception.ApiException;
import io.openware.common.payment.infra.client.CustomerClient;
import io.openware.common.payment.infra.mq.EventOutboxRelay;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.MqProducer;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import java.math.BigDecimal;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 退款幂等集成测试（真跑 H2 + 真实事务），覆盖冻结前 must-fix：
 * <ul>
 *   <li>P0-A：重复登记线下退款返回 409 {@code REFUND_STATE_INVALID}（不是 500），且不二次回冲订单已收；</li>
 *   <li>P0-B：同 Idempotency-Key 重放返回同一退款单；不带键/不同键的同订单同金额重复申请 409
 *       {@code REFUND_DUPLICATE}，DB 退款行数不增。</li>
 * </ul>
 *
 * <p>断言走原生 SQL：本测试要证明写入端真的把列写对了，经生产读路径自证会掩盖读写同一处错误。
 */
@SpringBootTest
@ActiveProfiles("test")
class RefundIdempotencyIntegrationTest {

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
  private RefundApplicationService refundService;

  @Autowired
  private DataSource dataSource;

  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 9L, 0));
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  /**
   * P0-A：第一次线下退款完成后重复调用 → 409，且订单已收金额不被第二次回冲、pay_refund 仍只有一行且为 REFUNDED。
   * 用部分退款（3000/8000）断言，否则全额退款后二次回冲也被 GREATEST(0,...) 掩盖。
   */
  @Test
  void markRefunded_duplicate_returns409AndDoesNotDoubleRollback() {
    long orderId = 9401L;
    insertPaidOrder(orderId, 8000L);
    RefundDto requested = refundService.requestRefund(TENANT_ID, STORE_ID, orderId,
        new BigDecimal("3000"), "e2e 部分退款", 7L, "idem-refund-9401");
    refundService.approveRefund(requested.id(), new BigDecimal("3000"), 8L);

    RefundDto refunded = refundService.markRefunded(requested.id(), "OFFLINE-9401", 9L);
    assertEquals("REFUNDED", refunded.status());
    assertEquals(0, new BigDecimal("5000").compareTo(order(orderId).paidAmount()));
    assertEquals("PARTIAL", order(orderId).refundStatus());

    ApiException error = assertThrows(ApiException.class,
        () -> refundService.markRefunded(requested.id(), "OFFLINE-9401", 9L));

    assertEquals(409, error.getStatus());
    assertEquals("REFUND_STATE_INVALID", error.getCode());
    assertEquals(0, new BigDecimal("5000").compareTo(order(orderId).paidAmount()), "重复退款不得二次回冲");
    assertEquals(1, refundCount(orderId));
    assertEquals("REFUNDED", refundStatus(requested.id()));
  }

  /** P0-B：同 Idempotency-Key 重放返回首次退款单（同 id），DB 只有一行。 */
  @Test
  void requestRefund_sameIdempotencyKey_replaysSameRow() {
    long orderId = 9402L;
    insertPaidOrder(orderId, 8000L);

    RefundDto first = refundService.requestRefund(TENANT_ID, STORE_ID, orderId,
        new BigDecimal("2000"), "e2e 重放", 7L, "idem-replay-9402");
    RefundDto replay = refundService.requestRefund(TENANT_ID, STORE_ID, orderId,
        new BigDecimal("2000"), "e2e 重放", 7L, "idem-replay-9402");

    assertEquals(first.id(), replay.id());
    assertEquals("PENDING", replay.status());
    assertEquals(1, refundCount(orderId));
  }

  /** P0-B：不带幂等键的同订单同金额重复申请 → 409 REFUND_DUPLICATE，DB 只有一行。 */
  @Test
  void requestRefund_duplicateWithoutKey_returns409AndSingleRow() {
    long orderId = 9403L;
    insertPaidOrder(orderId, 8000L);
    refundService.requestRefund(TENANT_ID, STORE_ID, orderId, new BigDecimal("1500"), "e2e 重复", 7L);

    ApiException error = assertThrows(ApiException.class, () -> refundService.requestRefund(
        TENANT_ID, STORE_ID, orderId, new BigDecimal("1500"), "e2e 重复", 7L));

    assertEquals(409, error.getStatus());
    assertEquals("REFUND_DUPLICATE", error.getCode());
    assertEquals(1, refundCount(orderId));
  }

  /** P0-B：不同幂等键但同订单同金额且已有未终结退款 → 409，DB 只有一行（不能靠幂等键绕过去重）。 */
  @Test
  void requestRefund_differentKeySameActiveAmount_returns409AndSingleRow() {
    long orderId = 9404L;
    insertPaidOrder(orderId, 8000L);
    refundService.requestRefund(TENANT_ID, STORE_ID, orderId,
        new BigDecimal("1200"), "e2e 不同键", 7L, "idem-key-a-9404");

    ApiException error = assertThrows(ApiException.class, () -> refundService.requestRefund(
        TENANT_ID, STORE_ID, orderId, new BigDecimal("1200"), "e2e 不同键", 7L, "idem-key-b-9404"));

    assertEquals(409, error.getStatus());
    assertEquals("REFUND_DUPLICATE", error.getCode());
    assertEquals(1, refundCount(orderId));
  }

  /** 幂等键复用但金额不同 → 409 IDEMPOTENCY_KEY_REUSED，不返回不匹配的退款单。 */
  @Test
  void requestRefund_reusedKeyDifferentAmount_returns409() {
    long orderId = 9405L;
    insertPaidOrder(orderId, 8000L);
    refundService.requestRefund(TENANT_ID, STORE_ID, orderId,
        new BigDecimal("1000"), "e2e 复用键", 7L, "idem-key-reuse-9405");

    ApiException error = assertThrows(ApiException.class, () -> refundService.requestRefund(
        TENANT_ID, STORE_ID, orderId, new BigDecimal("2000"), "e2e 复用键", 7L, "idem-key-reuse-9405"));

    assertEquals(409, error.getStatus());
    assertEquals("IDEMPOTENCY_KEY_REUSED", error.getCode());
    assertEquals(1, refundCount(orderId));
  }

  /**
   * 已全额退款（REFUNDED + ord_order paid=0/FULL）后再次提交同额申请（不带幂等键）：
   * 409 REFUND_DUPLICATE，pay_refund 行数不增（冻结前 E2E 硬断言场景）。
   */
  @Test
  void requestRefund_afterFullRefund_sameAmountReturns409WithoutNewRow() {
    long orderId = 9406L;
    insertPaidOrder(orderId, 8000L);
    RefundDto requested = refundService.requestRefund(TENANT_ID, STORE_ID, orderId,
        new BigDecimal("8000"), "e2e 全额退款", 7L, "idem-full-9406");
    refundService.approveRefund(requested.id(), new BigDecimal("8000"), 8L);
    refundService.markRefunded(requested.id(), "OFFLINE-9406", 9L);
    assertEquals("FULL", order(orderId).refundStatus());
    assertEquals(0, BigDecimal.ZERO.compareTo(order(orderId).paidAmount()));

    ApiException error = assertThrows(ApiException.class, () -> refundService.requestRefund(
        TENANT_ID, STORE_ID, orderId, new BigDecimal("8000"), "e2e 重复退款", 7L));

    assertEquals(409, error.getStatus());
    assertEquals("REFUND_DUPLICATE", error.getCode());
    assertEquals(1, refundCount(orderId));
  }

  /** 同额部分退款已完成后再次申请同额：409 REFUND_DUPLICATE（"前一单已 REFUNDED"不得重复建单）。 */
  @Test
  void requestRefund_afterPartialRefunded_sameAmountReturns409WithoutNewRow() {
    long orderId = 9407L;
    insertPaidOrder(orderId, 8000L);
    RefundDto requested = refundService.requestRefund(TENANT_ID, STORE_ID, orderId,
        new BigDecimal("3000"), "e2e 部分退款", 7L, "idem-partial-9407");
    refundService.approveRefund(requested.id(), new BigDecimal("3000"), 8L);
    refundService.markRefunded(requested.id(), "OFFLINE-9407", 9L);
    assertEquals("PARTIAL", order(orderId).refundStatus());

    ApiException error = assertThrows(ApiException.class, () -> refundService.requestRefund(
        TENANT_ID, STORE_ID, orderId, new BigDecimal("3000"), "e2e 重复部分退款", 7L));

    assertEquals(409, error.getStatus());
    assertEquals("REFUND_DUPLICATE", error.getCode());
    assertEquals(1, refundCount(orderId));
  }

  /** 同 Idempotency-Key 在退款已完成后重放：200 + 同 id 回放（不新建、不报错）。 */
  @Test
  void requestRefund_sameKeyAfterRefunded_replaysSameRow() {
    long orderId = 9408L;
    insertPaidOrder(orderId, 8000L);
    RefundDto first = refundService.requestRefund(TENANT_ID, STORE_ID, orderId,
        new BigDecimal("2500"), "e2e 完成后退款重放", 7L, "idem-done-9408");
    refundService.approveRefund(first.id(), new BigDecimal("2500"), 8L);
    refundService.markRefunded(first.id(), "OFFLINE-9408", 9L);

    RefundDto replay = refundService.requestRefund(TENANT_ID, STORE_ID, orderId,
        new BigDecimal("2500"), "e2e 完成后退款重放", 7L, "idem-done-9408");

    assertEquals(first.id(), replay.id());
    assertEquals("REFUNDED", replay.status());
    assertEquals(1, refundCount(orderId));
  }

  /** 重复审批（已 APPROVED 再 approve）→ 409 REFUND_STATE_INVALID，不再次迁移状态。 */
  @Test
  void approveRefund_duplicateReturns409() {
    long orderId = 9409L;
    insertPaidOrder(orderId, 8000L);
    RefundDto requested = refundService.requestRefund(TENANT_ID, STORE_ID, orderId,
        new BigDecimal("1000"), "e2e 重复审批", 7L, "idem-approve-9409");
    refundService.approveRefund(requested.id(), new BigDecimal("1000"), 8L);

    ApiException error = assertThrows(ApiException.class,
        () -> refundService.approveRefund(requested.id(), new BigDecimal("1000"), 8L));

    assertEquals(409, error.getStatus());
    assertEquals("REFUND_STATE_INVALID", error.getCode());
    assertEquals("APPROVED", refundStatus(requested.id()));
  }

  private void insertPaidOrder(long orderId, long amountMinor) {
    jdbc.update("INSERT INTO ord_order (id, tenant_id, store_id, trade_status, refund_status, currency_code,"
            + " total_amount, paid_amount, refundable_amount, discount_amount, tax_amount, updated_at)"
            + " VALUES (?, ?, ?, 'PAID', 'NONE', 'CNY', ?, ?, ?, 0, 0, CURRENT_TIMESTAMP)",
        orderId, TENANT_ID, STORE_ID, new BigDecimal(amountMinor),
        new BigDecimal(amountMinor), new BigDecimal(amountMinor));
  }

  private OrderRow order(long orderId) {
    return jdbc.queryForObject("SELECT trade_status, refund_status, paid_amount, refundable_amount"
            + " FROM ord_order WHERE tenant_id = ? AND id = ?",
        (rs, i) -> new OrderRow(rs.getString("trade_status"), rs.getString("refund_status"),
            rs.getBigDecimal("paid_amount"), rs.getBigDecimal("refundable_amount")),
        TENANT_ID, orderId);
  }

  private int refundCount(long orderId) {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM pay_refund WHERE tenant_id = ? AND order_id = ?",
        Integer.class, TENANT_ID, orderId);
    return count == null ? 0 : count;
  }

  private String refundStatus(long refundId) {
    return jdbc.queryForObject("SELECT status FROM pay_refund WHERE tenant_id = ? AND id = ?",
        String.class, TENANT_ID, refundId);
  }

  private record OrderRow(String tradeStatus, String refundStatus, BigDecimal paidAmount,
                          BigDecimal refundableAmount) {}
}
