package io.openware.common.payment.domain.cashier.repository;

import io.openware.common.payment.domain.cashier.model.ConfirmedCollectLine;
import io.openware.common.payment.domain.cashier.model.RefundCurrencyAggregate;
import io.openware.common.payment.domain.intent.model.PaymentIntent;
import io.openware.common.payment.domain.transaction.model.PaymentTransaction;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 交班/日结汇总仓储端口。
 *
 * <p>把"收银对账要读哪些事实"收在一个端口里，而不是让应用层逐个查询聚合：
 * 这些读法（按窗口、按币种分组、左闭右开）是**同一件事（对账）**的组成部分，
 * 分开注入只会让每个装配点多几个依赖。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或持久化行对象（见 standards/10“端口命名与契约”）：
 * 返回的都是领域模型或领域投影。
 */
public interface CashierReportRepository {

  /**
   * 汇总某班次窗口内的**现金**收款（{@code provider = CASH} 且成功）。
   *
   * <p>按币种过滤：把不同币种的现金静默相加会得出没有业务含义的应有现金。
   * 区间为**左闭右开**（{@code [from, to)}）：窗口端点是班次边界，闭区间会把同一笔算进相邻两班。
   *
   * @return 汇总金额；无记录时返回零（不返回 null，调用方不必判空）
   */
  BigDecimal sumCashSucceeded(Long tenantId, Long storeId, String currencyCode, LocalDateTime from,
                              LocalDateTime to);

  /**
   * 列出某门店在 {@code [from, to)} 内**成功**的支付意图（按创建时间、标识升序）。
   *
   * <p>状态过滤收在方法名里，而不是留给调用方拼条件：收款汇总只应统计成功收款，
   * {@code INIT}/{@code HOLD}/{@code FAILED} 是尝试而非收款——把它们算进已收金额会让日结凭空多出钱。
   */
  List<PaymentIntent> listSucceededIntentsBetween(Long tenantId, Long storeId, LocalDateTime from,
                                                  LocalDateTime to);

  /** 列出某门店在 {@code [from, to)} 内的支付交易（按发生时刻、标识升序）。 */
  List<PaymentTransaction> listTransactionsBetween(Long tenantId, Long storeId, LocalDateTime from,
                                                   LocalDateTime to);

  /**
   * 列出某门店在 {@code [from, to)} 内**已确认**的组合收款行（币种 + 分腿响应）。
   *
   * <p>只取 {@code CONFIRMED}：{@code INIT}/{@code FAILED} 尚未形成资金事实。
   * 聚合（拆分腿、按支付构成累加）由调用方完成——分腿结构属于统计口径，不是查询条件。
   */
  List<ConfirmedCollectLine> listConfirmedCollects(Long tenantId, Long storeId, LocalDateTime from,
                                                   LocalDateTime to);

  /** 退款按币种聚合（{@code [from, to)}）。 */
  List<RefundCurrencyAggregate> summarizeRefunds(Long tenantId, Long storeId, LocalDateTime from,
                                                 LocalDateTime to);
}
