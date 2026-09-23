package io.openware.common.payment.channel.spi;

/** 创建支付意图结果；clientSecret/rawPayload 为渠道返回凭证与原文。 */
public record CreatePaymentIntentResult(
        String provider,
        String transactionId,
        String status,
        String clientSecret,
        String rawPayload) {
}
