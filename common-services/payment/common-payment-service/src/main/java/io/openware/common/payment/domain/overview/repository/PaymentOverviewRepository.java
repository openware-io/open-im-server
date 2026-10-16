package io.openware.common.payment.domain.overview.repository;

import io.openware.common.payment.domain.cashier.model.RefundCurrencyAggregate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 总部汇总的只读端口（按渠道 / 币种汇总成功交易与已退款记录）。
 *
 * <p>按 standards/10，跨表聚合查询定义**专用只读端口**：不暴露 MyBatis Wrapper、PO、Mapper 或持久化行对象，
 * 返回具名领域投影。
 *
 * <p>币种一律参与分组：把 CNY 与 USD 的金额相加会得出没有业务含义的合计。
 */
public interface PaymentOverviewRepository {

  /**
   * 交易汇总行。
   *
   * @param provider         渠道
   * @param currencyCode     币种
   * @param transactionCount 成功交易笔数
   * @param amount           金额合计（最小货币单位）
   */
  record TransactionSummary(String provider, String currencyCode, long transactionCount, BigDecimal amount) {
  }

  /**
   * 汇总成功交易（{@code [from, to)}），按渠道 + 币种分组。
   *
   * @param storeIds 门店范围；{@code null} 或空表示不限门店（总部视角）
   */
  List<TransactionSummary> summarizeSucceededTransactions(Long tenantId, List<Long> storeIds,
                                                          LocalDateTime from, LocalDateTime to);

  /**
   * 汇总已退款记录（{@code [from, to)}），按币种分组。
   *
   * <p>复用 {@link RefundCurrencyAggregate}：它与"退款按币种聚合"是同一件事，
   * 两处各建一个同形 record 只会让调用方在两种类型间来回转换。
   *
   * @param storeIds 门店范围；{@code null} 或空表示不限门店
   */
  List<RefundCurrencyAggregate> summarizeRefunds(Long tenantId, List<Long> storeIds,
                                                 LocalDateTime from, LocalDateTime to);
}
