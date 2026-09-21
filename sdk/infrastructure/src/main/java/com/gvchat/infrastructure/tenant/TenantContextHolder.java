package com.gvchat.infrastructure.tenant;

/**
 * 租户上下文线程持有器。请求进入时由 Gateway/Admin 解析并 set，领域服务/Repository 读取。
 */
public final class TenantContextHolder {
    private static final ThreadLocal<TenantContext> HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> TOKEN_HOLDER = new ThreadLocal<>();

    private TenantContextHolder() {}

    public static void set(TenantContext ctx) { HOLDER.set(ctx); }

    public static void set(TenantContext ctx, String token) {
        HOLDER.set(ctx);
        TOKEN_HOLDER.set(token);
    }

    public static TenantContext get() { return HOLDER.get(); }

    public static Long tenantIdOrNull() {
        TenantContext ctx = HOLDER.get();
        return ctx == null ? null : ctx.tenantId();
    }

    public static String tokenOrNull() { return TOKEN_HOLDER.get(); }

    public static void clear() {
        HOLDER.remove();
        TOKEN_HOLDER.remove();
    }
}
