package io.openware.platform.admin.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.currency.Currency;
import io.openware.platform.admin.api.context.ContextDtos.ContextItem;
import io.openware.platform.admin.api.context.ContextDtos.ContextsResponse;
import io.openware.platform.admin.api.context.ContextDtos.SelectContextRequest;
import io.openware.platform.admin.api.context.ContextDtos.SelectContextResponse;
import io.openware.platform.admin.infra.TenantIamDomainClient;
import io.openware.platform.admin.infra.security.AdminContextHolder;
import io.openware.platform.admin.infra.security.AdminSessionCookie;
import io.openware.platform.admin.infra.security.AdminTenantContextTokenSigner;
import io.openware.platform.admin.infra.security.SaaAdminSessionStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SaaS 后台租户上下文选择 BFF：/admin/contexts + /admin/context/select（网关 /api/v1/admin/** 路由到本服务）。
 * 从 Redis 会话解析 platformAccountId（不再信任明文账号头），直接调用 tenant-service 内部 IAM 端点。
 */
@RestController
@RequestMapping("/admin")
public class ContextController {

    private final TenantIamDomainClient tenantIamClient;
    private final AdminTenantContextTokenSigner tokenSigner;
    private final SaaAdminSessionStore sessionStore;

    public ContextController(TenantIamDomainClient tenantIamClient, AdminTenantContextTokenSigner tokenSigner,
                             SaaAdminSessionStore sessionStore) {
        this.tenantIamClient = tenantIamClient;
        this.tokenSigner = tokenSigner;
        this.sessionStore = sessionStore;
    }

    @GetMapping("/contexts")
    public ContextsResponse contexts() {
        Long platformAccountId = platformAccountId();
        if (platformAccountId == null) {
            return new ContextsResponse(List.of());
        }
        try {
            List<ContextItem> items = tenantIamClient.contexts(platformAccountId).stream()
                    .map(c -> new ContextItem(c.contextId(), c.tenantId(), c.tenantName(),
                            c.organizationId(), c.organizationName(), c.storeId(), c.storeName(),
                            c.roles(), c.scopeType()))
                    .toList();
            return new ContextsResponse(items);
        } catch (Exception e) {
            throw new ApiException(502, "TENANT_IAM_UNAVAILABLE", "获取运营上下文失败");
        }
    }

    @PostMapping("/context/select")
    public SelectContextResponse select(@RequestBody SelectContextRequest request, HttpServletRequest servletRequest) {
        Long platformAccountId = platformAccountId();
        if (platformAccountId == null) {
            throw new ApiException(401, "ADMIN_SESSION_MISSING", "缺少平台账号会话");
        }
        Scope scope;
        try {
            scope = Scope.parse(request.contextId());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "CONTEXT_ID_INVALID", "contextId 格式非法: " + request.contextId());
        }
        try {
            TenantIamDomainClient.Context selected = tenantIamClient.contexts(platformAccountId).stream()
                    .filter(context -> request.contextId().equals(context.contextId()))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(403, "CONTEXT_FORBIDDEN", "无权选择此运营上下文"));
            // 选择/切换上下文时强制刷新权限快照：后台刚调整的角色权限立即生效，不受 5 分钟缓存影响
            TenantIamDomainClient.PermissionSnapshot snapshot = tenantIamClient.permissions(platformAccountId,
                    scope.tenantId(), scope.organizationId(), scope.storeId(), true);
            if (snapshot.authorizationVersion() <= 0 || snapshot.permissions().isEmpty()) {
                throw new ApiException(403, "CONTEXT_FORBIDDEN", "此运营上下文已无有效权限");
            }
            String sessionId = sessionId(servletRequest);
            // 币种（规范 §3.1）：读目标租户配置（缺省 USD），既写进签名 token 的 currency claim，
            // 也随响应体回给前端作为全局 store 的唯一启动来源。查询失败不影响上下文选择。
            String currencyCode = Currency.parse(tenantIamClient.tenantCurrencyCode(scope.tenantId())).code();
            // scopeType 随上下文一起签发：平台账号为 PLATFORM（可跨租户看审计），租户账号为 TENANT。
            sessionStore.setActiveContext(sessionId, tokenSigner.sign(platformAccountId, scope.tenantId(),
                    scope.organizationId(), scope.storeId(), snapshot.authorizationVersion(), snapshot.permissions(),
                    selected.scopeType(), currencyCode), request.contextId());
            return new SelectContextResponse(scope.tenantId(), scope.organizationId(), scope.storeId(),
                    platformAccountId, snapshot.authorizationVersion(), snapshot.permissions(), currencyCode);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception e) {
            throw new ApiException(502, "TENANT_IAM_UNAVAILABLE", "获取权限快照失败");
        }
    }

    private static String sessionId(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (var cookie : request.getCookies()) {
            if (AdminSessionCookie.NAME.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private Long platformAccountId() {
        return AdminContextHolder.get() == null ? null : AdminContextHolder.get().platformAccountId();
    }

    /** contextId 格式：tenantId:organizationId:storeId（空段表示 null）。 */
    private record Scope(Long tenantId, Long organizationId, Long storeId) {
        static Scope parse(String contextId) {
            if (contextId == null || contextId.isBlank()) {
                throw new IllegalArgumentException("contextId is required");
            }
            String[] parts = contextId.split(":", -1);
            if (parts.length != 3) throw new IllegalArgumentException("invalid contextId segments");
            Long tenantId = Long.parseLong(parts[0]);
            Long organizationId = parts.length > 1 && !parts[1].isEmpty() ? Long.parseLong(parts[1]) : null;
            Long storeId = parts.length > 2 && !parts[2].isEmpty() ? Long.parseLong(parts[2]) : null;
            return new Scope(tenantId, organizationId, storeId);
        }
    }
}
