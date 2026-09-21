package com.gvchat.infrastructure.currency;

import java.util.Locale;

/**
 * 受支持币种（{@code docs/standards/16_CURRENCY_CONVENTIONS.md} §2）：全仓唯一符号/小数位来源。
 *
 * <p>任何模块**不得**自建符号表，也不得在业务代码里写 {@code "¥"}、{@code "元"}、{@code "CNY"}、{@code "RMB"} 字面量。
 * 新增币种只扩展本枚举，不允许散落映射。
 *
 * <p>最小货币单位口径：DB 存最小货币单位（CNY 分 / USD cent）；禁止任何汇率换算。
 */
public enum Currency {
    /** 人民币：符号 ¥，两位小数。 */
    CNY("¥", 2, "人民币"),
    /** 美元：符号 $，两位小数。 */
    USD("$", 2, "美元");

    /** 缺省币种：租户未配置（无行/空值/非法值）时一律按 USD。 */
    public static final Currency DEFAULT = USD;

    private final String symbol;
    private final int minorUnitDigits;
    private final String displayName;

    Currency(String symbol, int minorUnitDigits, String displayName) {
        this.symbol = symbol;
        this.minorUnitDigits = minorUnitDigits;
        this.displayName = displayName;
    }

    /** ISO 4217 代码，即枚举名（{@code CNY} / {@code USD}）。 */
    public String code() {
        return name();
    }

    /** 展示符号（{@code ¥} / {@code $}）。 */
    public String symbol() {
        return symbol;
    }

    /** 最小货币单位小数位（CNY/USD 均为 2）。 */
    public int minorUnitDigits() {
        return minorUnitDigits;
    }

    /** 币种名称（{@code 人民币} / {@code 美元}），用于确需中文单位的文案。 */
    public String displayName() {
        return displayName;
    }

    /**
     * 解析币种代码，**未知值/null/空值一律回退 {@link #DEFAULT}（USD）**，绝不抛给业务。
     *
     * @param raw 币种代码（大小写不敏感，允许首尾空白）
     * @return 已识别的币种，或 {@link #DEFAULT}
     */
    public static Currency parse(String raw) {
        return fromCode(raw).orElse(DEFAULT);
    }

    /** 严格解析：仅当值属于受支持币种时返回值，否则抛 400 语义的异常由调用方翻译。 */
    public static boolean isSupported(String raw) {
        return fromCode(raw).isPresent();
    }

    private static java.util.Optional<Currency> fromCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (Currency currency : values()) {
            if (currency.name().equals(normalized)) {
                return java.util.Optional.of(currency);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * 把最小货币单位金额渲染为「符号 + 两位小数」的展示文案（服务端拼文案的唯一入口）。
     *
     * <p>负数把负号放在符号前（{@code -¥1.00}），避免历史实现 {@code ¥-1.00} 这种反直觉写法。
     * 大额不加千分位，保持与既有服务端文案（例如计价 {@code displayText}）逐字兼容。
     */
    public String format(long minorAmount) {
        long absolute = Math.abs(minorAmount);
        long unit = 1L;
        for (int i = 0; i < minorUnitDigits; i++) {
            unit *= 10L;
        }
        String sign = minorAmount < 0 ? "-" : "";
        long whole = absolute / unit;
        long fraction = absolute % unit;
        return sign + symbol + whole + "." + String.format(Locale.ROOT, "%0" + minorUnitDigits + "d", fraction);
    }
}
