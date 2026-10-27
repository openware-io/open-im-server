package io.openware.common.payment.application;

/**
 * 渠道回调结算命令（payment-channel-service → 本服务内部接口的入参）。
 *
 * <p>字段口径与渠道 SPI {@code VerifyCallbackResult} 对齐，另带 orderId（渠道 {@code out_trade_no}）：
 * <ul>
 *   <li>{@code amountMinor}：渠道回调金额，**最小货币单位整数**（与压缩后的 {@code pay_intent.amount}
 *       口径一致）；</li>
 *   <li>{@code refundAmount}：退款回调金额，缺省按 {@code amountMinor} 处理（全额退款）；</li>
 *   <li>{@code status}：渠道状态，接受 {@code SUCCEEDED}/{@code REFUNDED}/{@code FAILED}/
 *       {@code CLOSED}/{@code PROCESSING}。</li>
 * </ul>
 *
 * @param provider      渠道标识：ALIPAY / WECHAT / STRIPE（大小写不敏感）
 * @param transactionId 渠道交易号（幂等键一部分，必填）
 * @param eventId       渠道回调事件 ID（可选：Stripe event id / 微信 v3 通知 id / 支付宝 notify_id）
 * @param tenantId      租户（可选：渠道回调未携带时按订单租户解析，携带时必须与订单一致）
 * @param storeId       门店（可选：渠道回调不携带，携带时必须与订单门店一致）
 * @param orderId       业务订单标识（渠道 out_trade_no，必填）
 * @param amountMinor   结算金额（最小货币单位；收款成功必填且必须等于订单剩余应收）
 * @param currency      币种（可选：缺省按订单币种快照补齐；提供时必须与订单一致）
 * @param status        渠道回调状态
 * @param refundAmount  退款金额（退款回调缺省等于 {@code amountMinor}）
 */
public record ChannelSettlementCommand(
    String provider,
    String transactionId,
    String eventId,
    Long tenantId,
    Long storeId,
    String orderId,
    Long amountMinor,
    String currency,
    String status,
    Long refundAmount) {
}
