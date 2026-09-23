package io.openware.infrastructure.currency;

/**
 * 当前请求币种的线程持有器：与 {@code TenantContextHolder} 同款旁路 holder。
 *
 * <p>由**同一个已验证签名 token 的过滤器**（{@code TenantContextFilter}）写入与清理；这样不必修改
 * {@code TenantContext} record 的规范构造器（全仓上百处构造调用），却与租户上下文同生共死。
 *
 * <p>线程安全：ThreadLocal 天然线程隔离；过滤器 {@code finally} 必定 clear，避免线程池复用串味。
 * 未设置时读取方一律拿到 {@link Currency#DEFAULT}（USD），因此老 token / 无上下文请求不会 401。
 */
public final class CurrencyContextHolder {
    private static final ThreadLocal<Currency> HOLDER = new ThreadLocal<>();

    private CurrencyContextHolder() {}

    /** 写入当前请求币种（null 视为清除，避免把「未知」当成一个可读取的状态）。 */
    public static void set(Currency currency) {
        if (currency == null) {
            HOLDER.remove();
            return;
        }
        HOLDER.set(currency);
    }

    /** 当前请求币种，缺省 {@link Currency#DEFAULT}（USD）。 */
    public static Currency get() {
        Currency currency = HOLDER.get();
        return currency == null ? Currency.DEFAULT : currency;
    }

    /** 当前请求是否有显式写入币种（诊断用；业务判断请用 {@link #get()}）。 */
    public static Currency getOrNull() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
