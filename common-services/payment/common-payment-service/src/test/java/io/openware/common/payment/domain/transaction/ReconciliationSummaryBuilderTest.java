package io.openware.common.payment.domain.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.common.payment.domain.transaction.model.PaymentTransaction;
import io.openware.common.payment.domain.transaction.service.ReconciliationSummaryBuilder;
import io.openware.common.payment.domain.transaction.service.ReconciliationSummaryBuilder.ReconciliationSummary;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 对账摘要聚合（纯领域）。
 *
 * <p>核心不变量是**币种必须参与分组**（standards/16 §5）：把 CNY 与 USD 的毛额静默相加会得出一个
 * 没有业务含义的净额。规则此前内联在应用服务里，只能通过 mock mapper 验证；提取后可以直接穷举。
 */
class ReconciliationSummaryBuilderTest {

  private static final LocalDateTime FROM = LocalDateTime.of(2026, 3, 1, 0, 0);
  private static final LocalDateTime TO = LocalDateTime.of(2026, 3, 2, 0, 0);

  @Test
  void groupsByProviderAndCurrency() {
    ReconciliationSummary summary = ReconciliationSummaryBuilder.summarize(List.of(
        tx("ALIPAY", "CNY", "100", "2"),
        tx("ALIPAY", "CNY", "200", "4"),
        tx("WECHAT", "CNY", "50", "1")), FROM, TO);

    assertEquals(2, summary.lines().size(), "两个渠道各一行");
    assertEquals("ALIPAY", summary.lines().get(0).provider());
    assertEquals(2, summary.lines().get(0).count());
    assertEquals(new BigDecimal("300"), summary.lines().get(0).grossAmount());
    assertEquals(new BigDecimal("6"), summary.lines().get(0).feeAmount());
    assertEquals(new BigDecimal("294"), summary.lines().get(0).netAmount());
    assertEquals(new BigDecimal("350"), summary.total().grossAmount());
    assertFalse(summary.mixedCurrency());
    assertEquals("CNY", summary.currencyCode());
  }

  /**
   * 混币种：同一渠道必须拆成两行，且信封显式标注 mixedCurrency、currencyCode 置空。
   *
   * <p>这是本类最重要的断言：若把 CNY 与 USD 合成一行，得到的"净额"没有业务含义，
   * 而调用方无法从数字上察觉。
   */
  @Test
  void sameProviderInTwoCurrenciesYieldsSeparateLines() {
    ReconciliationSummary summary = ReconciliationSummaryBuilder.summarize(List.of(
        tx("ALIPAY", "CNY", "100", "2"),
        tx("ALIPAY", "USD", "300", "5")), FROM, TO);

    assertEquals(2, summary.lines().size(), "同一渠道的不同币种不得合并");
    assertEquals("CNY", summary.lines().get(0).currencyCode());
    assertEquals(new BigDecimal("100"), summary.lines().get(0).grossAmount());
    assertEquals("USD", summary.lines().get(1).currencyCode());
    assertEquals(new BigDecimal("300"), summary.lines().get(1).grossAmount());
  }

  /** 混币种时信封与 total 行都必须标注为"跨币种合计"，不可当单一币种金额使用。 */
  @Test
  void mixedCurrencyMarksEnvelopeAndTotalAsCrossCurrency() {
    ReconciliationSummary summary = ReconciliationSummaryBuilder.summarize(List.of(
        tx("ALIPAY", "CNY", "100", "0"),
        tx("ALIPAY", "USD", "300", "0")), FROM, TO);

    assertTrue(summary.mixedCurrency());
    assertNull(summary.currencyCode(), "混币种时不得给出单币种标注");
    assertNull(summary.total().currencyCode(), "total 行同样标注为跨币种");
    assertEquals(2, summary.total().count());
  }

  /** 缺失手续费按零计，净额等于毛额。 */
  @Test
  void nullFeeTreatedAsZero() {
    ReconciliationSummary summary = ReconciliationSummaryBuilder.summarize(
        List.of(tx("ALIPAY", "CNY", "100", null)), FROM, TO);

    assertEquals(new BigDecimal("0"), summary.total().feeAmount());
    assertEquals(new BigDecimal("100"), summary.total().netAmount());
  }

  /** 无交易：总量为零，currencyCode 为空（没有任何币种事实），不算混币种。 */
  @Test
  void emptyTransactionsYieldZeroTotal() {
    ReconciliationSummary summary = ReconciliationSummaryBuilder.summarize(List.of(), FROM, TO);

    assertTrue(summary.lines().isEmpty());
    assertEquals(0, summary.total().count());
    assertEquals(BigDecimal.ZERO, summary.total().grossAmount());
    assertNull(summary.currencyCode());
    assertFalse(summary.mixedCurrency(), "没有币种事实时不算混币种");
  }

  /** 区间原样回显，便于调用方确认统计口径。 */
  @Test
  void echoesRange() {
    ReconciliationSummary summary = ReconciliationSummaryBuilder.summarize(List.of(), FROM, TO);

    assertEquals(FROM, summary.from());
    assertEquals(TO, summary.to());
  }

  private static PaymentTransaction tx(String provider, String currencyCode, String amount, String fee) {
    return new PaymentTransaction(1L, 100L, 10L, "KTV", 1L, provider, "T-" + provider, null,
        new BigDecimal(amount), currencyCode, BigDecimal.ONE,
        fee == null ? null : new BigDecimal(fee), PaymentTransaction.STATUS_SUCCEEDED, FROM, null);
  }
}
