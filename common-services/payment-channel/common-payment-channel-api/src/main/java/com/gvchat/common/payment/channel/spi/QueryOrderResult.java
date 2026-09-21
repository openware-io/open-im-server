package com.gvchat.common.payment.channel.spi;

/** 渠道订单查询结果；amount 为最小货币单位整数（分）。 */
public record QueryOrderResult(
        String provider,
        String transactionId,
        String orderId,
        String status,
        long amount,
        String currency) {
}
