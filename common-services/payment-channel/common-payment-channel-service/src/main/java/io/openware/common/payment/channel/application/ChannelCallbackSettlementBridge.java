package io.openware.common.payment.channel.application;

import io.openware.common.payment.channel.infra.client.ChannelSettlementClient;
import io.openware.common.payment.channel.infra.client.ChannelSettlementRequest;
import io.openware.common.payment.channel.infra.client.ChannelSettlementResponse;
import io.openware.common.payment.channel.infra.persistence.PaymentChannelTransactionRecorder;
import io.openware.common.payment.channel.spi.VerifyCallbackResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 渠道回调结算桥（渠道侧）：验签成功后把资金事实交给 payment-service 落账。
 *
 * <p>为什么放在渠道服务而不是让渠道服务直接写支付库：支付意图/交易与订单落账属于 payment 域，
 * 渠道服务只拥有渠道交易与回调原文；跨服务用内部签名接口（见 {@link ChannelSettlementClient}）。
 *
 * <p>失败语义：**不静默吞**——记 ERROR 日志、把 {@code pay_channel_transaction} 的结算状态置为
 * {@code FAILED} 并累加尝试次数，然后让调用方返回该渠道的失败 ack，使渠道按自身策略重投；
 * 重复回调会在 payment-service 命中幂等回放，不会重复记账。
 */
@Component
@Slf4j
public class ChannelCallbackSettlementBridge {

    /** 结算成功状态（pay_channel_transaction.settlement_status）。 */
    public static final String SETTLEMENT_APPLIED = "APPLIED";
    /** 结算失败、可重试状态。 */
    public static final String SETTLEMENT_FAILED = "FAILED";

    private static final int MAX_ERROR_LENGTH = 255;

    private final ChannelSettlementClient settlementClient;
    private final PaymentChannelTransactionRecorder recorder;

    public ChannelCallbackSettlementBridge(ChannelSettlementClient settlementClient,
                                           PaymentChannelTransactionRecorder recorder) {
        this.settlementClient = settlementClient;
        this.recorder = recorder;
    }

    /**
     * 结算一笔已验签的渠道回调。
     *
     * @return true=payment-service 已受理（含幂等回放/失败状态确认），按成功 ack；false=调用失败，必须让渠道重试
     */
    public boolean settle(String provider, VerifyCallbackResult result) {
        String transactionId = result.providerTransactionNo();
        try {
            ChannelSettlementResponse response = settlementClient.settle(new ChannelSettlementRequest(
                    provider,
                    transactionId,
                    result.eventId(),
                    result.tenantId(),
                    null,
                    result.orderId(),
                    result.amount() > 0 ? result.amount() : null,
                    result.currency(),
                    result.status(),
                    null));
            recorder.markSettlement(provider, transactionId, SETTLEMENT_APPLIED, null);
            log.info("渠道回调结算成功: provider={} transactionId={} orderId={} channelStatus={} "
                            + "settlementType={} applied={} replayed={} orderTradeStatus={} orderPaidAmount={}",
                    provider, transactionId, result.orderId(), result.status(), response.settlementType(),
                    response.applied(), response.replayed(), response.tradeStatus(), response.paidAmount());
            return true;
        } catch (RuntimeException failure) {
            markFailed(provider, transactionId, failure);
            // 验签已通过但结算失败：必须让渠道重试，同时保留完整原因（不打印报文与密钥）。
            log.error("渠道回调结算失败，返回可重试 ack: provider={} transactionId={} orderId={} channelStatus={} reason={}",
                    provider, transactionId, result.orderId(), result.status(), failure.toString(), failure);
            return false;
        }
    }

    private void markFailed(String provider, String transactionId, RuntimeException failure) {
        try {
            recorder.markSettlement(provider, transactionId, SETTLEMENT_FAILED, truncate(failure.getMessage()));
        } catch (RuntimeException recordFailure) {
            // 结算失败状态的记录本身失败时也必须让回调失败重试；原始失败原因已在 settle 的 log.error 中完整记录。
            log.error("渠道回调结算失败状态落库异常，需人工核对并重投: provider={} transactionId={}",
                    provider, transactionId, recordFailure);
        }
    }

    private static String truncate(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
