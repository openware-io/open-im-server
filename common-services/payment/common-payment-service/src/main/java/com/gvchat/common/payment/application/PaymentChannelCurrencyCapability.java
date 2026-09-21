package com.gvchat.common.payment.application;

import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.currency.Currency;

import java.util.Map;

/**
 * 支付渠道的**币种能力**（`docs/standards/16_CURRENCY_CONVENTIONS.md` §2.2.3）。
 *
 * <p>现金 / 储值 / 积分与币种无关（跟随订单快照币种）；线上渠道受币种约束：
 * 微信、支付宝**仅支持 CNY**，Stripe 以 USD 结算。租户币种不满足渠道能力时：
 * <ul>
 *   <li>可用支付方式列表带 {@code available:false + unavailableReason}（{@link PaymentMethodApplicationService}）；</li>
 *   <li>组合收款在**落库之前**拒绝，返回 {@code CURRENCY_PAYMENT_METHOD_UNSUPPORTED}，
 *       绝不让用户走到渠道下单才失败（{@code CollectApplicationService#validateLegs}）。</li>
 * </ul>
 *
 * <p>这里是渠道能力的唯一定义；渠道实现类里的 {@code defaultCurrency("CNY")} 是**渠道协议回退**，
 * 不是业务默认值，不要改成 USD。
 */
public final class PaymentChannelCurrencyCapability {

    /** 渠道 → 唯一受支持币种；不在表中的渠道与币种无关。 */
    private static final Map<String, Currency> CHANNEL_CURRENCY = Map.of(
            "ALIPAY", Currency.CNY,
            "WECHAT", Currency.CNY,
            "STRIPE", Currency.USD);

    private PaymentChannelCurrencyCapability() {
    }

    /** 该渠道要求的币种；与币种无关的渠道返回 null。 */
    public static Currency requiredCurrency(String method) {
        return method == null ? null : CHANNEL_CURRENCY.get(method);
    }

    /** 该渠道在当前币种下是否可用（未知币种按 USD 解释，与全局缺省一致）。 */
    public static boolean isSupported(String method, String currencyCode) {
        Currency required = requiredCurrency(method);
        return required == null || required == Currency.parse(currencyCode);
    }

    /** 组合收款的落库前校验：渠道币种能力不满足直接拒绝。 */
    public static void requireSupported(String method, String currencyCode) {
        Currency required = requiredCurrency(method);
        if (required == null) {
            return;
        }
        Currency actual = Currency.parse(currencyCode);
        if (required != actual) {
            throw new ApiException(422, "CURRENCY_PAYMENT_METHOD_UNSUPPORTED",
                    "支付方式 " + method + " 仅支持 " + required.code() + "，当前币种为 " + actual.code());
        }
    }

    /** 不可用原因文案（可用时为 null）。 */
    public static String unavailableReason(String method, String currencyCode) {
        Currency required = requiredCurrency(method);
        if (required == null || required == Currency.parse(currencyCode)) {
            return null;
        }
        return "该渠道仅支持 " + required.code() + "，当前租户币种为 " + Currency.parse(currencyCode).code();
    }
}
