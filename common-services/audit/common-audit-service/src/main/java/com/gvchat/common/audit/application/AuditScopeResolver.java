package com.gvchat.common.audit.application;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.audit.infra.security.AuditCallerScope;
import com.gvchat.common.audit.infra.security.AuditCallerScopeHolder;
import com.gvchat.infrastructure.tenant.PermissionGuard;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 审计查询视角解析器：权限分层在**服务端**强制生效，前端传什么都不能扩大可见范围。
 *
 * <p>规则（SAAS_PLATFORM_02 §4.1 / §9.4）：
 * <ol>
 *   <li>必须有 {@code audit.view}：没有签名上下文或上下文里没有该权限码 → 403 PERMISSION_DENIED；
 *       「缺少任何身份信息就放行平台视角」是不可接受的越权面，因此无上下文一律拒绝；</li>
 *   <li>平台视角：上下文 {@code scopeType=PLATFORM}（平台账号，平台运营选择租户上下文时同样成立）
 *       或上下文没有租户约束（{@code tenantId<=0}）；可看全部租户，允许 {@code tenantId} 过滤；</li>
 *   <li>租户视角：只返回当前上下文租户的记录；客户端传了别的 {@code tenantId} → 403
 *       AUDIT_TENANT_FORBIDDEN 并留 WARN（越权尝试必须可见，不做静默降级）。</li>
 * </ol>
 */
@Slf4j
@Component
public class AuditScopeResolver {

    /** 审计查询权限码：平台角色 platform.operator 与租户角色 tenant.owner 均已授予。 */
    public static final String PERMISSION_VIEW = "audit.view";

    /** 解析结果：视角 + 生效租户（租户视角才有值）。 */
    public record Resolution(AuditScope scope, Long tenantId, Long accountId) {

        public boolean isPlatform() {
            return scope.isPlatform();
        }
    }

    /**
     * 解析当前调用方视角。
     *
     * @param requestedTenantId 查询参数里的 tenantId（可为 null）
     */
    public Resolution resolve(Long requestedTenantId) {
        PermissionGuard.require(PERMISSION_VIEW);
        TenantContext context = TenantContextHolder.get();
        AuditCallerScope callerScope = AuditCallerScopeHolder.get();
        boolean noTenantConstraint = context.tenantId() <= 0;
        boolean platform = noTenantConstraint || (callerScope != null && callerScope.isPlatform());
        if (platform) {
            return new Resolution(AuditScope.PLATFORM, null, context.accountId());
        }
        if (requestedTenantId != null && requestedTenantId != context.tenantId()) {
            log.warn("审计查询越权尝试被拒绝: accountId={}, contextTenantId={}, requestedTenantId={}",
                    context.accountId(), context.tenantId(), requestedTenantId);
            throw new ApiException(403, "AUDIT_TENANT_FORBIDDEN", "只能查看本租户的审计日志");
        }
        return new Resolution(AuditScope.TENANT, context.tenantId(), context.accountId());
    }
}
