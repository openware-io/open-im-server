package io.openware.common.payment.channel.spi;

/** 渠道订单查询请求。 */
public record QueryOrderRequest(String transactionId, String orderId) {
}
