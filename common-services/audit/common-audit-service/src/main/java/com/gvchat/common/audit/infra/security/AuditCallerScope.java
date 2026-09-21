package com.gvchat.common.audit.infra.security;

/**
 * 签名上下文中的调用方视角（{@code X-Tenant-Context} 的 {@code scopeType} 声明）。
 *
 * <p>由 IAM 快照在「选择运营上下文」时判定：平台账号（绑定 platform 级角色）为 {@code PLATFORM}，
 * 租户/门店账号为 {@code TENANT}/{@code STORE}。缺失时按 {@code TENANT} 处理（失败收敛到更小范围）。
 */
public record AuditCallerScope(String scopeType, long tenantId) {

    public static final String SCOPE_PLATFORM = "PLATFORM";

    public boolean isPlatform() {
        return scopeType != null && SCOPE_PLATFORM.equalsIgnoreCase(scopeType);
    }
}
