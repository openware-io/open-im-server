package io.openware.common.payment.channel.spi;

/** 创建支付意图请求；amount 为最小货币单位整数（分）。 */
public record CreatePaymentIntentRequest(
        Long tenantId,
        Long storeId,
        String orderId,
        long amount,
        String currency,
        String idempotencyKey) {
}
