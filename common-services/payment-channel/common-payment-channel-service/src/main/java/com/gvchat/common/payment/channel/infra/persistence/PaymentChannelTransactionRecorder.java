package com.gvchat.common.payment.channel.infra.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gvchat.common.payment.channel.infra.persistence.mapper.PayChannelTransactionMapper;
import com.gvchat.common.payment.channel.infra.persistence.po.PayChannelTransactionPo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Locale;

/** 渠道交易幂等记录器：以 (provider, transaction_id) 与 (provider, callback_event_id) 双唯一键去重，回调重复投递只落一条资金事实。 */
@Component
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

    /** 记录渠道交易；并发重复由 uk_pay_channel_txn_provider / uk_pay_channel_txn_event 唯一键兜底，冲突视为幂等命中。 */
    public void record(String provider, String transactionId, String eventId, Long tenantId, String orderId, long amount,
                       String currency, String status, String callbackRaw) {
        PayChannelTransactionPo po = new PayChannelTransactionPo();
        po.setTenantId(tenantId == null ? 0L : tenantId);
        po.setTransactionId(transactionId);
        po.setCallbackEventId(eventId == null || eventId.isBlank() ? null : eventId);
        po.setProvider(normalize(provider));
        po.setOrderId(orderId);
        po.setAmount(amount);
        po.setCurrency(currency == null || currency.isBlank() ? "CNY" : currency);
        po.setStatus(status == null || status.isBlank() ? "PROCESSING" : status);
        po.setCallbackRaw(callbackRaw);
        LocalDateTime now = LocalDateTime.now();
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        try {
            transactionMapper.insert(po);
        } catch (DuplicateKeyException ignored) {
            // 并发重复回调由唯一键兜底，视为幂等命中
        }
    }

    private static String normalize(String provider) {
        return provider == null ? "" : provider.toUpperCase(Locale.ROOT);
    }
}
