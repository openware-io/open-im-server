package io.openware.common.payment.channel.spi;

/**
 * 渠道回调验签与解析结果；verified=false 时不得改变任何资金事实。
 *
 * <p>{@code eventId} 与 {@code tenantId} 是**验签成功后**才可采信的上下文：前者用于重放去重与追溯
 * （Stripe event id / 微信 v3 通知 id / 支付宝 notify_id，微信 v2 无事件 ID 时为空），
 * 后者供结算桥做跨租户 scope 校验（来自渠道下单时的 passback/attach/metadata；未携带为空）。
 */
public record VerifyCallbackResult(
        boolean verified,
        String providerTransactionNo,
        String orderId,
        long amount,
        String currency,
        String status,
        String rawPayload,
        String eventId,
        Long tenantId) {
}
