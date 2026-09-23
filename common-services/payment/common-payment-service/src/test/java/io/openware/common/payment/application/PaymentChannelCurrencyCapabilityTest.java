package io.openware.common.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openware.common.exception.ApiException;
import org.junit.jupiter.api.Test;

/**
 * 支付渠道币种能力（规范 §2.2.3）：现金/储值/积分与币种无关；微信/支付宝仅 CNY；Stripe 以 USD 结算。
 * 不匹配时必须在落库前以 CURRENCY_PAYMENT_METHOD_UNSUPPORTED 拒绝。
 */
class PaymentChannelCurrencyCapabilityTest {

    @Test
    void currencyAgnosticMethodsAreAlwaysSupported() {
        for (String method : new String[] {"CASH", "WALLET", "POINT", null}) {
            assertThat(PaymentChannelCurrencyCapability.isSupported(method, "CNY")).isTrue();
            assertThat(PaymentChannelCurrencyCapability.isSupported(method, "USD")).isTrue();
            assertThat(PaymentChannelCurrencyCapability.requiredCurrency(method)).isNull();
        }
    }

    @Test
    void wechatAndAlipayOnlySupportCny() {
        assertThat(PaymentChannelCurrencyCapability.isSupported("ALIPAY", "CNY")).isTrue();
        assertThat(PaymentChannelCurrencyCapability.isSupported("WECHAT", "CNY")).isTrue();
        assertThat(PaymentChannelCurrencyCapability.isSupported("ALIPAY", "USD")).isFalse();
        assertThat(PaymentChannelCurrencyCapability.isSupported("WECHAT", "USD")).isFalse();
    }

    @Test
    void stripeIsSettledInUsd() {
        assertThat(PaymentChannelCurrencyCapability.isSupported("STRIPE", "USD")).isTrue();
        assertThat(PaymentChannelCurrencyCapability.isSupported("STRIPE", "CNY")).isFalse();
    }

    /** 未知/缺失币种按 USD 解释（与全局缺省一致），不抛给业务。 */
    @Test
    void unknownCurrencyFallsBackToUsdSemantics() {
        assertThat(PaymentChannelCurrencyCapability.isSupported("STRIPE", "RMB")).isTrue();
        assertThat(PaymentChannelCurrencyCapability.isSupported("ALIPAY", null)).isFalse();
        assertThat(PaymentChannelCurrencyCapability.isSupported("ALIPAY", "RMB")).isFalse();
    }

    @Test
    void requireSupportedRejectsUsdTenantUsingAlipay() {
        assertThatThrownBy(() -> PaymentChannelCurrencyCapability.requireSupported("ALIPAY", "USD"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(422);
                    assertThat(((ApiException) ex).getCode()).isEqualTo("CURRENCY_PAYMENT_METHOD_UNSUPPORTED");
                });
        // 匹配与无关渠道都不抛
        PaymentChannelCurrencyCapability.requireSupported("ALIPAY", "CNY");
        PaymentChannelCurrencyCapability.requireSupported("CASH", "USD");
    }

    @Test
    void unavailableReasonIsOnlyPresentWhenCurrencyMismatches() {
        assertThat(PaymentChannelCurrencyCapability.unavailableReason("ALIPAY", "CNY")).isNull();
        assertThat(PaymentChannelCurrencyCapability.unavailableReason("CASH", "USD")).isNull();
        assertThat(PaymentChannelCurrencyCapability.unavailableReason("ALIPAY", "USD"))
                .contains("CNY").contains("USD");
    }
}
