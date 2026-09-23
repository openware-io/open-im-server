package io.openware.infrastructure.currency;

/**
 * 当前请求币种的唯一读取入口（{@code docs/standards/16_CURRENCY_CONVENTIONS.md} §2、§3.3）。
 *
 * <p>取值顺序：签名上下文携带的币种（由 {@code TenantContextFilter} 从 JWT claim {@code currency} 解析）。
 * 缺省 USD，绝不因为缺 claim 而失败（老 token 兼容）。
 *
 * <p>**单据有币种快照时以快照为准**，本解析器只用于「服务端需要知道当前租户币种」的场景
 * （服务端拼的展示文案、报表表头、门店级默认值等）；历史单据不得用本解析器改写已固化的币种。
 */
public final class CurrencyResolver {
    private CurrencyResolver() {}

    /** 当前请求币种，缺省 {@link Currency#DEFAULT}（USD）。 */
    public static Currency current() {
        return CurrencyContextHolder.get();
    }

    /** 当前请求币种代码（{@code CNY} / {@code USD}）。 */
    public static String currentCode() {
        return current().code();
    }

    /** 当前请求币种符号。 */
    public static String currentSymbol() {
        return current().symbol();
    }

    /** 以当前币种渲染最小货币单位金额（服务端拼文案的唯一入口）。 */
    public static String format(long minorAmount) {
        return current().format(minorAmount);
    }

    /** 解析任意币种代码为受支持币种，未知值回退 USD。 */
    public static Currency parse(String raw) {
        return Currency.parse(raw);
    }
}
