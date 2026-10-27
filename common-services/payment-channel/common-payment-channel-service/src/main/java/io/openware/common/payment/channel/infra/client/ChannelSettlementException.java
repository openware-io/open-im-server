package io.openware.common.payment.channel.infra.client;

/**
 * 渠道结算调用失败（签名配置缺失 / 序列化失败 / 网络不可达 / 非 2xx）。
 *
 * <p>刻意不吞：调用方据此把回调 ack 置为「可重试」，并把失败原因落 {@code pay_channel_transaction}
 * 的结算状态列，供重投与人工核对。
 */
public class ChannelSettlementException extends RuntimeException {

    public ChannelSettlementException(String message) {
        super(message);
    }

    public ChannelSettlementException(String message, Throwable cause) {
        super(message, cause);
    }
}
