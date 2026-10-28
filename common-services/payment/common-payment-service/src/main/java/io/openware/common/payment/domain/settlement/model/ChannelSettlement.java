package io.openware.common.payment.domain.settlement.model;

import java.math.BigDecimal;

/**
 * 渠道回调结算事实（幂等台账）。
 *
 * <p>一笔渠道交易在同一 {@code kind} 下只允许一条结算事实：收款回调（{@code PAYMENT}）与退款回调
 * （{@code REFUND}）分别幂等。这个事实是「渠道回调 → pay_intent/pay_transaction + ord_order 落账」
 * 的唯一判据——重复回调命中它即回放成功，不再重复记账。
 *
 * <p>审计字段（createdBy/createdAt/updatedBy/updatedAt）不在此承载：由持久化适配器补齐。
 *
 * @param id               结算记录标识
 * @param tenantId         租户
 * @param storeId          门店（来自订单口径，渠道回调不携带）
 * @param orderId          业务订单标识
 * @param provider         渠道：ALIPAY / WECHAT / STRIPE
 * @param transactionId    渠道交易号（幂等键的一部分）
 * @param eventId          渠道回调事件 ID（Stripe event id / 微信 v3 通知 id / 支付宝 notify_id，追溯用）
 * @param kind             结算类型：{@code PAYMENT} / {@code REFUND}
 * @param amount           本次结算金额（最小货币单位）
 * @param currencyCode     币种快照（必须与订单币种一致）
 * @param status           结算结果状态：{@code SUCCEEDED} / {@code REFUNDED}
 * @param payIntentId      本次收款落库的支付意图标识（退款回调为空）
 * @param payTransactionId 本次收款落库的交易标识（退款回调为空）
 */
public record ChannelSettlement(
    Long id,
    Long tenantId,
    Long storeId,
    Long orderId,
    String provider,
    String transactionId,
    String eventId,
    String kind,
    BigDecimal amount,
    String currencyCode,
    String status,
    Long payIntentId,
    Long payTransactionId) {

  /** 收款结算：成功回调落 pay_intent + pay_transaction + 订单收款。 */
  public static final String KIND_PAYMENT = "PAYMENT";

  /** 退款结算：退款成功回调回冲订单已收/可退。 */
  public static final String KIND_REFUND = "REFUND";

  /** 收款结算成功。 */
  public static final String STATUS_SUCCEEDED = "SUCCEEDED";

  /** 退款结算成功。 */
  public static final String STATUS_REFUNDED = "REFUNDED";
}
