package io.openware.common.payment.channel.infra.persistence;
import io.openware.infrastructure.currency.Currency;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.openware.common.payment.channel.infra.persistence.mapper.PayChannelTransactionMapper;
import io.openware.common.payment.channel.infra.persistence.po.PayChannelTransactionPo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Locale;

/** 渠道交易幂等记录器：以 (provider, transaction_id) 与 (provider, callback_event_id) 双唯一键去重，回调重复投递只落一条资金事实。 */
@Component
@Slf4j
public class PaymentChannelTransactionRecorder {

    private final PayChannelTransactionMapper transactionMapper;

    public PaymentChannelTransactionRecorder(PayChannelTransactionMapper transactionMapper) {
        this.transactionMapper = transactionMapper;
    }

    /** 判断渠道交易是否已存在（幂等去重命中）。 */
    public boolean exists(String provider, String transactionId) {
        if (transactionId == null || transactionId.isBlank()) {
            return false;
        }
        Long count = transactionMapper.selectCount(new LambdaQueryWrapper<PayChannelTransactionPo>()
                .eq(PayChannelTransactionPo::getProvider, normalize(provider))
                .eq(PayChannelTransactionPo::getTransactionId, transactionId));
        return count != null && count > 0;
    }

    /** 判断回调事件是否已处理（Stripe event id / 微信 v3 通知 id / 支付宝 notify_id 重放去重）。 */
    public boolean existsByEventId(String provider, String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return false;
        }
        Long count = transactionMapper.selectCount(new LambdaQueryWrapper<PayChannelTransactionPo>()
                .eq(PayChannelTransactionPo::getProvider, normalize(provider))
                .eq(PayChannelTransactionPo::getCallbackEventId, eventId));
        return count != null && count > 0;
    }

    /** 记录渠道交易（无回调事件 ID 的场景）。 */
    public void record(String provider, String transactionId, Long tenantId, String orderId, long amount,
                       String currency, String status, String callbackRaw) {
        record(provider, transactionId, null, tenantId, orderId, amount, currency, status, callbackRaw);
    }

    /** 记录渠道交易；并发重复由 uk_pay_channel_transaction_provider_transaction / uk_pay_channel_transaction_provider_event 唯一键兜底，冲突视为幂等命中。 */
    public void record(String provider, String transactionId, String eventId, Long tenantId, String orderId, long amount,
                       String currency, String status, String callbackRaw) {
        PayChannelTransactionPo po = new PayChannelTransactionPo();
        po.setTenantId(tenantId == null ? 0L : tenantId);
        po.setTransactionId(transactionId);
        po.setCallbackEventId(eventId == null || eventId.isBlank() ? null : eventId);
        po.setProvider(normalize(provider));
        po.setOrderId(orderId);
        po.setAmount(amount);
        po.setCurrency(currency == null || currency.isBlank() ? Currency.DEFAULT.code() : currency);
        po.setStatus(status == null || status.isBlank() ? "PROCESSING" : status);
        po.setCallbackRaw(callbackRaw);
        LocalDateTime now = LocalDateTime.now();
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        try {
            transactionMapper.insert(po);
        }
        // degradation: 并发重复回调由唯一键兜底，视为幂等命中
        catch (DuplicateKeyException ignored) {
            // 并发重复回调由唯一键兜底，视为幂等命中
        }
    }

    /**
     * 记录一次渠道回调结算调用结果（幂等：按 provider + transaction_id 更新既有渠道交易）。
     *
     * <p>结算失败必须落 {@code FAILED} + 尝试次数 + 原因，让"验签通过但没落账"的回调可被重投、可被检索；
     * 结算成功清空上一次失败原因。渠道交易行不存在（例如微信 v3 仅成功事件落库）时更新 0 行，属预期。
     */
    public void markSettlement(String provider, String transactionId, String settlementStatus, String error) {
        if (transactionId == null || transactionId.isBlank()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<PayChannelTransactionPo> update = new LambdaUpdateWrapper<PayChannelTransactionPo>()
                .eq(PayChannelTransactionPo::getProvider, normalize(provider))
                .eq(PayChannelTransactionPo::getTransactionId, transactionId)
                .set(PayChannelTransactionPo::getSettlementStatus, settlementStatus)
                .set(PayChannelTransactionPo::getSettlementError, error)
                .set(PayChannelTransactionPo::getSettlementUpdatedAt, now)
                .set(PayChannelTransactionPo::getUpdatedAt, now)
                .setSql("settlement_attempts = settlement_attempts + 1");
        try {
            transactionMapper.update(null, update);
        } catch (RuntimeException failure) {
            // 结算状态是"可重试/可观测"的辅助事实；写失败不能伪装成成功，由调用方抛给上层决定 ack。
            log.error("渠道交易结算状态更新失败: provider={} transactionId={} settlementStatus={}",
                    provider, transactionId, settlementStatus, failure);
            throw failure;
        }
    }

    private static String normalize(String provider) {
        return provider == null ? "" : provider.toUpperCase(Locale.ROOT);
    }
}
