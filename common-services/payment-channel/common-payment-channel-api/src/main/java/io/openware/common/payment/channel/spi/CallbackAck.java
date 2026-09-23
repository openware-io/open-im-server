package io.openware.common.payment.channel.spi;

/**
 * 渠道回调确认报文：让各渠道返回正确的 ack 语义。
 * 支付宝 text/plain success|fail、微信 v2 XML、微信 v3 / Stripe 以 HTTP 状态码表达。
 */
public record CallbackAck(int httpStatus, String contentType, String body) {

    public static CallbackAck ok(String contentType, String body) {
        return new CallbackAck(200, contentType, body);
    }

    public static CallbackAck fail(String contentType, String body) {
        return new CallbackAck(400, contentType, body);
    }
}
