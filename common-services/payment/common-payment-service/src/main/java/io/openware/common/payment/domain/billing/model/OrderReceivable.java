package io.openware.common.payment.domain.billing.model;

import java.math.BigDecimal;

/**
 * 订单应收（账单）读模型：收款/退款判定所需的最小字段集。
 *
 * <p>按 standards/10，它是**具名投影类型**：由一条带计算列的查询派生
 * （总额在 {@code total_amount} 为 0 时按明细汇总并减去优惠加税），字段集不等于任何单张表。
 *
 * <p>只承载本域真正读取的列。订单上下文的其余字段（明细、客户、履约状态机等）
 * 不属于支付域，加进来只会造出一个没人用的宽模型。
 *
 * @param orderId      订单标识
 * @param tradeStatus  订单交易状态（{@code ord_order.trade_status}）：
 *                     {@code CANCELLED}/{@code VOIDED}/{@code COMPLETED} 不可再收款
 * @param totalAmount  应收总额（最小货币单位）
 * @param paidAmount   已收金额（最小货币单位）
 * @param currencyCode 订单币种快照（收款与退款都必须与它一致，禁止跨币种）
 */
public record OrderReceivable(
    Long orderId,
    String tradeStatus,
    BigDecimal totalAmount,
    BigDecimal paidAmount,
    String currencyCode) {

  /** 订单已取消。 */
  public static final String TRADE_CANCELLED = "CANCELLED";

  /** 订单已作废。 */
  public static final String TRADE_VOIDED = "VOIDED";

  /** 订单已完成。 */
  public static final String TRADE_COMPLETED = "COMPLETED";

  /** 总额；为空视为零。 */
  public BigDecimal totalOrZero() {
    return totalAmount == null ? BigDecimal.ZERO : totalAmount;
  }

  /** 已收；为空视为零。 */
  public BigDecimal paidOrZero() {
    return paidAmount == null ? BigDecimal.ZERO : paidAmount;
  }

  /** 剩余应收 = 总额 − 已收，下限为零（多收不退负）。 */
  public long remainingPayable() {
    return totalOrZero().subtract(paidOrZero()).max(BigDecimal.ZERO).longValueExact();
  }

  /**
   * 该订单当前是否**可收款**。
   *
   * <p>已取消/已作废/已完成/已全额收款都不可再收款：对它们落账会把钱记到一个已经终结（或已收清）的订单上。
   * 判据是交易状态（订单主表唯一的状态来源，兼容列 {@code status} 已随收口迁移删除）**加**剩余应收：
   * 新模型全额收款后是 {@code trade_status=PAID}（不再是旧的 COMPLETED），只看状态名会把「已收清」
   * 当成可收款，于是“新幂等键 + payable=0”的重复 collect 还能写出 0 元收款单——
   * 这里用剩余应收归零作为第二道判据，恢复旧行为（重复收款 422 ORDER_STATUS_INVALID）。
   */
  public boolean collectable() {
    return remainingPayable() > 0
        && !TRADE_CANCELLED.equals(tradeStatus)
        && !TRADE_VOIDED.equals(tradeStatus)
        && !TRADE_COMPLETED.equals(tradeStatus);
  }
}
