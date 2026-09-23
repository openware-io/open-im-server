package io.openware.common.audit.application;

/**
 * 审计查询视角：平台视角可跨租户，租户视角被强制收敛到当前上下文租户。
 *
 * <p>判定依据只有服务端可验证的签名上下文（{@code X-Tenant-Context}）：
 * <ul>
 *   <li>{@code scopeType=PLATFORM}（IAM 快照判定为平台账号）或上下文没有租户约束（{@code tenantId<=0}）→ 平台视角；</li>
 *   <li>其余一律租户视角，并按 {@code tenant_id = 上下文租户} 强制过滤。</li>
 * </ul>
 */
public enum AuditScope {
    PLATFORM,
    TENANT;

    public boolean isPlatform() {
        return this == PLATFORM;
    }
}
