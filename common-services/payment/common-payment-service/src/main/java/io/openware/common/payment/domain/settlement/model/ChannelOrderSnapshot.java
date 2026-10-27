package io.openware.common.payment.domain.settlement.model;

import java.math.BigDecimal;

/**
 * 渠道结算所需的订单快照（读模型）。
 *
 * <p>渠道回调只带 {@code orderId}，不含门店/租户/币种：这些口径必须以订单主表为准，
 * 由本快照在**加锁前后各读一次**——加锁前用于 scope 校验与租户解析，加锁后用于金额/状态判定
 * 与响应回读。字段集是具名投影，不等于 {@code ord_order} 的任意一行。
 *
 * @param orderId      订单标识
 * @param tenantId     订单租户（回调租户必须与它一致，否则拒绝）
 * @param storeId      订单门店（回调门店若提供则必须与它一致）
 * @param tradeStatus  交易状态：{@code CANCELLED}/{@code VOIDED}/{@code COMPLETED} 不可再收款
 * @param totalAmount  应收总额（最小货币单位）
 * @param paidAmount   已收金额（最小货币单位）
 * @param refundableAmount 可退金额（最小货币单位）
 * @param refundStatus 退款状态：{@code NONE}/{@code PARTIAL}/{@code FULL}
 * @param currencyCode 订单币种快照（收款/退款必须与它一致，禁止跨币种）
 */
public record ChannelOrderSnapshot(
    Long orderId,
    Long tenantId,
    Long storeId,
    String tradeStatus,
    BigDecimal totalAmount,
    BigDecimal paidAmount,
    BigDecimal refundableAmount,
    String refundStatus,
    String currencyCode) {

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
}
