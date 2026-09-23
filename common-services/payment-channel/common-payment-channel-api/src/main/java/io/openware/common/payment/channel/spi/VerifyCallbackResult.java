package io.openware.common.payment.channel.spi;

/** 渠道回调验签与解析结果；verified=false 时不得改变任何资金事实。 */
public record VerifyCallbackResult(
        boolean verified,
        String providerTransactionNo,
        String orderId,
        long amount,
        String currency,
        String status,
        String rawPayload) {
}
