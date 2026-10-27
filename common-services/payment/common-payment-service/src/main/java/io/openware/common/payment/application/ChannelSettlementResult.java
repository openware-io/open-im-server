package io.openware.common.payment.application;

import java.math.BigDecimal;

/**
 * 渠道回调结算结果（内部接口响应）。
 *
 * <p>{@code applied=true} 表示本次请求**真的**改写了 pay_intent/pay_transaction/ord_order；
 * {@code replayed=true} 表示命中幂等台账、直接回放首次成功的结算（订单状态原样返回）。
 * 失败/关闭类回调返回 {@code applied=false, replayed=false}，且不改动订单收款事实。
 *
 * @param provider         渠道标识（大写归一）
 * @param transactionId    渠道交易号
 * @param eventId          渠道回调事件 ID（可为空）
 * @param orderId          业务订单标识
 * @param channelStatus    渠道回调状态
 * @param settlementType   PAYMENT / REFUND / NONE（失败/关闭类回调不对账本做任何写入）
 * @param applied          是否真的落账
 * @param replayed         是否命中幂等回放
 * @param payIntentId      本次落账的支付意图标识（仅收款成功）
 * @param payTransactionId 本次落账的支付交易标识（仅收款成功）
 * @param tradeStatus      订单交易状态（回放时为当前值）
 * @param refundStatus     订单退款状态（回放时为当前值）
 * @param paidAmount       订单已收金额（最小货币单位）
 * @param currencyCode     结算币种（订单币种快照）
 * @param settlementAmount 本次结算金额（最小货币单位）
 */
public record ChannelSettlementResult(
    String provider,
    String transactionId,
    String eventId,
    Long orderId,
    String channelStatus,
    String settlementType,
    boolean applied,
    boolean replayed,
    Long payIntentId,
    Long payTransactionId,
    String tradeStatus,
    String refundStatus,
    BigDecimal paidAmount,
    String currencyCode,
    BigDecimal settlementAmount) {
}
