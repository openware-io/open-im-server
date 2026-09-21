package com.gvchat.common.payment.channel.application;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.payment.channel.spi.CallbackAck;
import com.gvchat.common.payment.channel.spi.ChannelCapability;
import com.gvchat.common.payment.channel.spi.CreatePaymentIntentRequest;
import com.gvchat.common.payment.channel.spi.CreatePaymentIntentResult;
import com.gvchat.common.payment.channel.spi.PaymentChannelProvider;
import com.gvchat.common.payment.channel.spi.QueryOrderRequest;
import com.gvchat.common.payment.channel.spi.QueryOrderResult;
import com.gvchat.common.payment.channel.spi.VerifyCallbackRequest;
import com.gvchat.common.payment.channel.spi.VerifyCallbackResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 支付渠道应用服务：按 provider 路由到对应渠道适配器。 */
@Service
public class ChannelProviderApplicationService {

    private final Map<String, PaymentChannelProvider> providers;

    public ChannelProviderApplicationService(List<PaymentChannelProvider> providerList) {
        this.providers = providerList.stream()
                .collect(Collectors.toMap(PaymentChannelProvider::provider, Function.identity()));
    }

    /** 渠道能力查询（默认全 disabled）。 */
    public List<ChannelCapability> available() {
        return providers.values().stream().map(PaymentChannelProvider::capability).toList();
    }

    public CreatePaymentIntentResult createIntent(String provider, CreatePaymentIntentRequest request) {
        return resolveEnabled(provider).createPaymentIntent(request);
    }

    public VerifyCallbackResult verifyCallback(String provider, VerifyCallbackRequest request) {
        return resolveEnabled(provider).verifyCallback(request);
    }

    public QueryOrderResult query(String provider, QueryOrderRequest request) {
        return resolveEnabled(provider).queryOrder(request);
    }

    /** 生成渠道回调确认报文（支付宝 success/fail、微信 v2 XML、微信 v3/Stripe HTTP 状态码）。 */
    public CallbackAck callbackAck(String provider, VerifyCallbackResult result) {
        return resolve(provider).callbackAck(result);
    }

    private PaymentChannelProvider resolveEnabled(String provider) {
        PaymentChannelProvider p = resolve(provider);
        if (!p.capability().enabled()) {
            throw new ApiException(422, "PAYMENT_CHANNEL_DISABLED", "支付渠道未启用: " + p.provider());
        }
        return p;
    }

    private PaymentChannelProvider resolve(String provider) {
        if (provider == null) {
            throw new ApiException(404, "PAYMENT_CHANNEL_UNKNOWN", "缺少支付渠道参数");
        }
        PaymentChannelProvider p = providers.get(provider.toLowerCase(Locale.ROOT));
        if (p == null) {
            throw new ApiException(404, "PAYMENT_CHANNEL_UNKNOWN", "未知支付渠道: " + provider);
        }
        return p;
    }
}
