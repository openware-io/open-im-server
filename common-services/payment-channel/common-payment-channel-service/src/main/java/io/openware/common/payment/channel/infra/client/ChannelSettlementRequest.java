package io.openware.common.payment.channel.infra.client;

/**
 * 发送给 payment-service 内部结算接口的请求体（字段名与 {@code ChannelSettlementCommand} 对齐）。
 *
 * @param provider      渠道标识（大写归一由服务端完成）
 * @param transactionId 渠道交易号（幂等键）
 * @param eventId       渠道回调事件 ID（可空）
 * @param tenantId      下单时回传的租户上下文（可空；服务端按订单租户校验/补齐）
 * @param storeId       门店（渠道回调不携带，通常为空；服务端按订单门店校验）
 * @param orderId       业务订单号（渠道 out_trade_no，字符串以兼容渠道口径）
 * @param amountMinor   渠道回调金额（最小货币单位整数）
 * @param currency      币种（可空）
 * @param status        渠道状态：SUCCEEDED/REFUNDED/FAILED/CLOSED/PROCESSING
 * @param refundAmount  退款金额（退款回调可空，服务端缺省按 amountMinor 全额退款）
 */
public record ChannelSettlementRequest(
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
