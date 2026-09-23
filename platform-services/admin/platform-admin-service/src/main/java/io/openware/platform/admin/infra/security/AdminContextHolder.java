package io.openware.platform.admin.infra.security;

/** SaaS 后台管理员上下文线程持有器。 */
public final class AdminContextHolder {

    private static final ThreadLocal<AdminContext> HOLDER = new ThreadLocal<>();

    private AdminContextHolder() {
    }

    public static void set(AdminContext context) {
        HOLDER.set(context);
    }

    public static AdminContext get() {
        return HOLDER.get();
    }

    public static Long accountIdOrNull() {
        AdminContext ctx = HOLDER.get();
        return ctx == null ? null : ctx.accountId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
