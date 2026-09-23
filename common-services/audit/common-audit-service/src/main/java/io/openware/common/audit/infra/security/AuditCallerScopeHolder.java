package io.openware.common.audit.infra.security;

/**
 * 审计调用方视角持有器：与 {@code TenantContextHolder} 同生命周期（请求线程内有效）。
 *
 * <p>审计服务需要区分「平台账号」与「租户账号」，而 SDK 的租户上下文只带 tenantId/permissions，
 * 因此这里单独持有签名上下文里的 {@code scopeType}，不改动被所有服务共用的 {@code TenantContext} 契约。
 */
public final class AuditCallerScopeHolder {

    private static final ThreadLocal<AuditCallerScope> HOLDER = new ThreadLocal<>();

    private AuditCallerScopeHolder() {}

    public static void set(AuditCallerScope scope) {
        HOLDER.set(scope);
    }

    public static AuditCallerScope get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
