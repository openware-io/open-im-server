package io.openware.common.payment.domain.transaction.service;

import io.openware.common.payment.domain.transaction.model.PaymentTransaction;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对账摘要聚合（纯领域，无 Spring、无持久化）。
 *
 * <p>按「渠道 + 币种」汇总成功交易（笔数/毛额/手续费/净额）。
 *
 * <p><b>币种是分组键的一部分，这是本类的核心不变量</b>（standards/16 §5）：把 CNY 与 USD 的毛额/
 * 手续费静默相加，会得出一个**没有业务含义的"净额"**。因此每个渠道按币种各出一行，
 * 信封上再给出单币种 {@code currencyCode} 与 {@code mixedCurrency} 标记；
 * 混币种时 {@code currencyCode} 为 {@code null}，{@code total} 行同样显式标注为跨币种合计，
 * **绝不能被当成单一币种金额使用**。
 */
public final class ReconciliationSummaryBuilder {

  private ReconciliationSummaryBuilder() {
  }

  /**
   * 渠道汇总行。
   *
   * @param currencyCode 本行金额的币种快照；{@code total} 行在混币种时为 {@code null}（= 跨币种合计）
   */
  public record ProviderLine(String provider, String currencyCode, long count, BigDecimal grossAmount,
                             BigDecimal feeAmount, BigDecimal netAmount) {
  }

  /**
   * 对账摘要信封。
   *
   * @param currencyCode  单币种时即该币种；混币种时为 {@code null}
   * @param mixedCurrency 是否存在多种币种（true 时调用方必须按行内 currencyCode 分别展示，不得相加）
   */
  public record ReconciliationSummary(LocalDateTime from, LocalDateTime to, String currencyCode,
                                      boolean mixedCurrency, List<ProviderLine> lines, ProviderLine total) {
  }

  /**
   * 汇总给定交易。
   *
   * <p>入参的交易 ID 必须已解析成规范币种编码（适配器/调用方负责把空值或小写归一），
   * 否则同一币种会因写法不同被拆成两行。
   */
  public static ReconciliationSummary summarize(List<PaymentTransaction> transactions, LocalDateTime from,
                                               LocalDateTime to) {
    Map<String, ProviderLine> byProviderCurrency = new LinkedHashMap<>();
    Set<String> currencies = new LinkedHashSet<>();
    long totalCount = 0;
    BigDecimal totalGross = BigDecimal.ZERO;
    BigDecimal totalFee = BigDecimal.ZERO;
    for (PaymentTransaction tx : transactions) {
      BigDecimal fee = tx.feeOrZero();
      String provider = tx.provider();
      String currencyCode = tx.currencyCode();
      // 分组键含币种：不同币种绝不合并成一行。
      String groupKey = provider + "|" + currencyCode;
      ProviderLine prev = byProviderCurrency.get(groupKey);
      ProviderLine next = prev == null
          ? new ProviderLine(provider, currencyCode, 1, tx.amount(), fee, tx.netAmount())
          : new ProviderLine(provider, currencyCode, prev.count() + 1,
              prev.grossAmount().add(tx.amount()), prev.feeAmount().add(fee),
              prev.netAmount().add(tx.netAmount()));
      byProviderCurrency.put(groupKey, next);
      currencies.add(currencyCode);
      totalCount++;
      totalGross = totalGross.add(tx.amount());
      totalFee = totalFee.add(fee);
    }
    // 只有恰好一种币种时才给出单币种标注；否则显式置空，提示"这是跨币种合计"。
    String currencyCode = currencies.size() == 1 ? currencies.iterator().next() : null;
    boolean mixedCurrency = currencies.size() > 1;
    ProviderLine total = new ProviderLine("TOTAL", currencyCode, totalCount, totalGross, totalFee,
        totalGross.subtract(totalFee));
    return new ReconciliationSummary(from, to, currencyCode, mixedCurrency,
        new ArrayList<>(byProviderCurrency.values()), total);
  }
}
