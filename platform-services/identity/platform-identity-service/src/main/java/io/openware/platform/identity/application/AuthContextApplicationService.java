package io.openware.platform.identity.application;

import io.openware.platform.identity.api.dto.AuthContextDtos.ContextItem;
import io.openware.platform.identity.api.dto.AuthContextDtos.SelectContextResponse;
import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.infrastructure.currency.Currency;
import io.openware.platform.identity.infra.client.TenantServiceClient;
import io.openware.platform.identity.infra.security.SaasAppRegistry;
import io.openware.platform.identity.infra.security.TenantContextTokenSigner;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;

/**
 * 经营上下文选择：调用 tenant-service 内部端点返回真实 contexts；
 * 选择上下文后签发 30 分钟签名 JWT（含 tenantId/organizationId/storeId/authorizationVersion/permissions）。
 * tenant-service 不可用时返回可诊断的服务不可用错误，不伪造权限结果。
 */
@Slf4j
@Service
public class AuthContextApplicationService {
    private final TenantServiceClient tenantClient;
    private final TenantContextTokenSigner tokenSigner;
    private final SaasAppRegistry appRegistry;

    /** 运营后台类应用(B 端)的运营角色集合：租户在 SaaS 后台注册该 IM 账号为运营人员即绑定这些角色之一。 */
    static final List<String> OPERATOR_ROLES = List.of("tenant.owner", "store.manager", "store.cashier", "store.finance");

    public AuthContextApplicationService(TenantServiceClient tenantClient, TenantContextTokenSigner tokenSigner,
                                         SaasAppRegistry appRegistry) {
        this.tenantClient = tenantClient;
        this.tokenSigner = tokenSigner;
        this.appRegistry = appRegistry;
    }

    /** 返回账号可访问的经营上下文（按 tenant-service 的 iam_user_role 聚合）。 */
    public List<ContextItem> contexts(Long accountId) {
        return contexts(accountId, null);
    }

    public List<ContextItem> contexts(Long accountId, String appId) {
        if (accountId == null) {
            return List.of();
        }
        try {
            if (appId != null && !appId.isBlank()) {
                if (!appRegistry.isBackend(appId)) {
                    // C 端：consumer 授权给出 app 租户；该租户下必须有 ACTIVE CUSTOMER membership。
                    TenantServiceClient.TenantContext consumer = tenantClient.consumerContext(accountId, appId);
                    if (consumer == null || consumer.tenantId() == null) {
                        throw new ApiException(HttpStatusCodes.FORBIDDEN, "CUSTOMER_NOT_BOUND",
                            "当前账号未绑定为租户客户");
                    }
                    requireCustomer(accountId, consumer.tenantId());
                    return List.of(toContextItem(consumer));
                }
                // 运营后台类应用（如 saas-b /b/）：审核 = 该账号是否已在 SaaS 租户后台
                // 注册为运营人员。B 端原生员工没有 consumer 授权锚点，因此不能强制要求 consumerContext；
                // 有锚点时仍按锚点过滤，避免 H5 跨租户看到无关上下文。
                List<TenantServiceClient.TenantContext> operatorContexts = tenantClient.contexts(accountId);
                log.info("B contexts accountId={} appId={} operatorContexts={}", accountId, appId, operatorContexts);
                if (operatorContexts == null || operatorContexts.isEmpty()) {
                    throw staffNotBound();
                }
                TenantServiceClient.TenantContext anchor = null;
                try {
                    anchor = tenantClient.consumerContext(accountId, appId);
                // degradation: native B staff has no consumer authorization
                } catch (Exception ignored) {
                }
                if (anchor == null) {
                    List<ContextItem> items = operatorContexts.stream()
                            .filter(AuthContextApplicationService::hasOperatorRole)
                            .map(AuthContextApplicationService::toContextItem)
                            .toList();
                    if (items.isEmpty()) {
                        throw staffNotBound();
                    }
                    return items;
                }
                log.info("B contexts anchor={}", anchor);
                final TenantServiceClient.TenantContext anchorContext = anchor;
                List<ContextItem> items = operatorContexts.stream()
                        .filter(AuthContextApplicationService::hasOperatorRole)
                        .filter(ctx -> ctx.tenantId() != null && ctx.tenantId().equals(anchorContext.tenantId())
                                && (anchorContext.storeId() == null || ctx.storeId() == null
                                    || ctx.storeId().equals(anchorContext.storeId())))
                        .map(AuthContextApplicationService::toContextItem)
                        .toList();
                if (items.isEmpty()) {
                    throw staffNotBound();
                }
                return items;
            }
            List<TenantServiceClient.TenantContext> contexts = tenantClient.contexts(accountId);
            return contexts.stream()
                    .map(AuthContextApplicationService::toContextItem)
                    .toList();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatusCodes.SERVICE_UNAVAILABLE, "IAM_CONTEXT_UNAVAILABLE",
                    "权限上下文服务暂时不可用");
        }
    }

    private void requireCustomer(Long accountId, Long tenantId) {
        boolean customer = tenantId != null && tenantClient.memberships(accountId).stream()
            .anyMatch(membership -> "CUSTOMER".equals(membership.membershipType())
                && "ACTIVE".equals(membership.status())
                && tenantId.equals(membership.tenantId()));
        if (!customer) {
            throw new ApiException(HttpStatusCodes.FORBIDDEN, "CUSTOMER_NOT_BOUND",
                "当前账号未绑定为租户客户");
        }
    }

    private static ApiException staffNotBound() {
        return new ApiException(HttpStatusCodes.FORBIDDEN, "STAFF_NOT_BOUND",
                "当前账号未绑定为租户员工，请联系租户管理员");
    }

    private static boolean hasOperatorRole(TenantServiceClient.TenantContext ctx) {
        if (ctx.roles() == null) {
            return false;
        }
        return ctx.roles().stream().anyMatch(OPERATOR_ROLES::contains);
    }

    private static ContextItem toContextItem(TenantServiceClient.TenantContext c) {
        return new ContextItem(c.contextId(), c.tenantId(), c.tenantName(),
                c.organizationId(), c.organizationName(), c.storeId(), c.storeName(),
                c.roles() == null ? List.of() : c.roles(), c.scopeType());
    }

    /** 选择上下文：解析 contextId → 作用域，取权限快照后签发签名 Token。 */
    public SelectContextResponse select(Long accountId, String contextId) {
        return select(accountId, contextId, null);
    }

    public SelectContextResponse select(Long accountId, String contextId, String appId) {
        if (accountId == null || contextId == null || contextId.isBlank()) {
            throw new IllegalArgumentException("contextId is required");
        }
        final Scope scope;
        try {
            scope = Scope.parse(contextId);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatusCodes.BAD_REQUEST, "IAM_CONTEXT_INVALID",
                    "contextId 格式非法，应为 tenantId:organizationId:storeId");
        }
        try {
            if (contextId.startsWith("consumer:")) {
                requireCustomer(accountId, scope.tenantId());
            }
            TenantServiceClient.PermissionSnapshot snapshot = contextId.startsWith("consumer:")
                    ? (appId == null ? null : tenantClient.consumerPermissions(accountId, appId, contextId))
                    // 选择/切换上下文时强制刷新权限快照：刚调整的角色权限立即生效
                    : tenantClient.permissions(accountId, scope.tenantId(), scope.organizationId(), scope.storeId(), true);
            if (snapshot == null || !Objects.equals(accountId, snapshot.accountId())
                    || !Objects.equals(scope.tenantId(), snapshot.tenantId())
                    || !Objects.equals(scope.organizationId(), snapshot.organizationId())
                    || !Objects.equals(scope.storeId(), snapshot.storeId())
                    || snapshot.permissions() == null || snapshot.permissions().isEmpty()
                    || snapshot.authorizationVersion() <= 0) {
                throw new ApiException(HttpStatusCodes.FORBIDDEN, "IAM_CONTEXT_FORBIDDEN",
                        "当前账号未被授权访问所选上下文");
            }
            // 币种（规范 §3.1）：读目标租户配置（缺省 USD），既写进签名 token 的 currency claim，
            // 也随响应体回给前端作为全局 store 的唯一启动来源。查询失败回退 USD，不影响上下文选择。
            String currencyCode = Currency.parse(tenantClient.tenantCurrencyCode(scope.tenantId())).code();
            String clientApp = appId == null || appId.isBlank()
                    ? null : (appRegistry.isBackend(appId) ? "saas-b" : "saas-c");
            String token = tokenSigner.sign(accountId, scope.tenantId(), scope.organizationId(), scope.storeId(),
                    snapshot.authorizationVersion(), snapshot.permissions(), currencyCode, clientApp);
            long expiresAt = System.currentTimeMillis() / 1000 + 1800;
            return new SelectContextResponse(token, expiresAt, scope.tenantId(), scope.organizationId(),
                    scope.storeId(), snapshot.authorizationVersion(), snapshot.permissions(), List.of(), currencyCode);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatusCodes.SERVICE_UNAVAILABLE, "IAM_CONTEXT_UNAVAILABLE",
                    "权限上下文服务暂时不可用");
        }
    }

    /** contextId 格式：tenantId:organizationId:storeId（空段表示 null）。 */
    private record Scope(Long tenantId, Long organizationId, Long storeId) {
        static Scope parse(String contextId) {
            String[] parts = contextId.split(":", -1);
            if (parts.length == 5 && "consumer".equals(parts[0])) {
                if (parts[1].isBlank()) throw new IllegalArgumentException("consumer app is required");
                return new Scope(Long.parseLong(parts[2]), nullable(parts[3]), nullable(parts[4]));
            }
            Long tenantId = Long.parseLong(parts[0]);
            Long organizationId = parts.length > 1 ? nullable(parts[1]) : null;
            Long storeId = parts.length > 2 ? nullable(parts[2]) : null;
            return new Scope(tenantId, organizationId, storeId);
        }

        private static Long nullable(String value) {
            return value == null || value.isEmpty() ? null : Long.parseLong(value);
        }
    }
}
