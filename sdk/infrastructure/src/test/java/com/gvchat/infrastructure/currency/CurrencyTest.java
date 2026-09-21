package com.gvchat.infrastructure.currency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 币种枚举：符号/小数位/名称、未知值回退 USD、最小货币单位文案渲染。 */
class CurrencyTest {

    @Test
    void supportedCurrenciesExposeSymbolAndMinorUnitDigits() {
        assertThat(Currency.CNY.code()).isEqualTo("CNY");
        assertThat(Currency.CNY.symbol()).isEqualTo("¥");
        assertThat(Currency.CNY.minorUnitDigits()).isEqualTo(2);
        assertThat(Currency.CNY.displayName()).isEqualTo("人民币");

        assertThat(Currency.USD.code()).isEqualTo("USD");
        assertThat(Currency.USD.symbol()).isEqualTo("$");
        assertThat(Currency.USD.minorUnitDigits()).isEqualTo(2);
        assertThat(Currency.USD.displayName()).isEqualTo("美元");

        assertThat(Currency.DEFAULT).isEqualTo(Currency.USD);
    }

    @Test
    void parseFallsBackToUsdForUnknownBlankAndNull() {
        assertThat(Currency.parse(null)).isEqualTo(Currency.USD);
        assertThat(Currency.parse("")).isEqualTo(Currency.USD);
        assertThat(Currency.parse("   ")).isEqualTo(Currency.USD);
        assertThat(Currency.parse("RMB")).isEqualTo(Currency.USD);
        assertThat(Currency.parse("JPY")).isEqualTo(Currency.USD);
        assertThat(Currency.parse("usd ")).isEqualTo(Currency.USD);
        assertThat(Currency.parse("cny")).isEqualTo(Currency.CNY);
        assertThat(Currency.parse("USD")).isEqualTo(Currency.USD);
    }

    @Test
    void isSupportedOnlyAcceptsKnownCodes() {
        assertThat(Currency.isSupported("CNY")).isTrue();
        assertThat(Currency.isSupported("usd")).isTrue();
        assertThat(Currency.isSupported("RMB")).isFalse();
        assertThat(Currency.isSupported(null)).isFalse();
    }

    @Test
    void formatRendersSymbolAndTwoDecimals() {
        assertThat(Currency.CNY.format(0L)).isEqualTo("¥0.00");
        assertThat(Currency.CNY.format(18800L)).isEqualTo("¥188.00");
        assertThat(Currency.CNY.format(18850L)).isEqualTo("¥188.50");
        assertThat(Currency.CNY.format(5L)).isEqualTo("¥0.05");
        assertThat(Currency.USD.format(26000L)).isEqualTo("$260.00");
        assertThat(Currency.USD.format(123456789L)).isEqualTo("$1234567.89");
    }

    @Test
    void formatPutsMinusSignBeforeSymbol() {
        assertThat(Currency.CNY.format(-100L)).isEqualTo("-¥1.00");
        assertThat(Currency.USD.format(-5L)).isEqualTo("-$0.05");
    }
}
