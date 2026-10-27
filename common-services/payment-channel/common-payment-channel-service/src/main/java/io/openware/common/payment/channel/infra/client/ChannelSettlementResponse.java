package io.openware.common.payment.channel.infra.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * payment-service 内部结算接口的响应体。
 *
 * <p>只读取渠道侧需要观测的字段；未知字段忽略（服务端增加响应字段不应破坏渠道服务）。
 * {@code applied=true} 表示真实落账，{@code replayed=true} 表示重复回调命中幂等回放。
 *
 * @param provider         渠道标识（大写归一）
 * @param transactionId    渠道交易号
 * @param eventId          渠道回调事件 ID
 * @param orderId          业务订单标识
 * @param channelStatus    渠道回调状态
 * @param settlementType   PAYMENT / REFUND / NONE
 * @param applied          是否真实落账
 * @param replayed         是否命中幂等回放
 * @param payIntentId      支付意图标识（仅收款成功）
 * @param payTransactionId 支付交易标识（仅收款成功）
 * @param tradeStatus      订单交易状态
 * @param refundStatus     订单退款状态
 * @param paidAmount       订单已收金额（最小货币单位）
 * @param currencyCode     结算币种
 * @param settlementAmount 本次结算金额（最小货币单位）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChannelSettlementResponse(
    String provider,
    String transactionId,
    String eventId,
    Long orderId,
    String channelStatus,
    String settlementType,
    boolean applied,
    boolean replayed,
    Long payIntentId,
    Long payTransactionId,
    String tradeStatus,
    String refundStatus,
    BigDecimal paidAmount,
    String currencyCode,
    BigDecimal settlementAmount) {
}
