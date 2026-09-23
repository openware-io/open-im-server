package io.openware.platform.identity.infra.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;

import java.util.List;

/**
 * tenant-service 内部端点客户端（RestClient），base-url 来自环境变量 TENANT_SERVICE_BASE_URL。
 * 失败时抛出 IllegalStateException，由应用服务回退空列表/不透明 token。
 */
@Component
public class TenantServiceClient {

    private final RestClient restClient;

    public TenantServiceClient(@Value("${app.tenant-service.base-url:http://localhost:4110}") String baseUrl,
                               InternalServiceAuthenticationInterceptor authenticationInterceptor) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestInterceptor(authenticationInterceptor).build();
    }

    /** 账号可访问的经营上下文列表。 */
    public List<TenantContext> contexts(Long accountId) {
        TenantContext[] arr = restClient.get()
                .uri("/internal/iam/account/{accountId}/contexts", accountId)
                .retrieve()
                .body(TenantContext[].class);
        return arr == null ? List.of() : List.of(arr);
    }

    public TenantContext consumerContext(Long accountId, String appId) {
        return restClient.get().uri(uriBuilder -> uriBuilder.path("/internal/iam/consumer/{appId}/context")
                        .queryParam("accountId", accountId).build(appId))
                .retrieve().body(TenantContext.class);
    }

    public PermissionSnapshot consumerPermissions(Long accountId, String appId, String contextId) {
        return restClient.get().uri(uriBuilder -> uriBuilder
                        .path("/internal/iam/consumer/{appId}/permissions")
                        .queryParam("accountId", accountId).build(appId))
                .retrieve().body(PermissionSnapshot.class);
    }

    public void grantConsumerAuthorization(Long accountId, String appId, String scope) {
        restClient.post().uri(uriBuilder -> uriBuilder.path("/internal/iam/consumer/{appId}/authorizations")
                        .queryParam("accountId", accountId).queryParam("scope", scope == null ? "profile.basic" : scope)
                        .build(appId))
                .retrieve().toBodilessEntity();
    }

    /**
     * 账号在某作用域下的权限快照（权限码 + 授权版本）。
     * {@code forceRefresh=true} 用于上下文选择：跳过 tenant 侧缓存，保证刚调整的权限立即生效。
     */
    public PermissionSnapshot permissions(Long accountId, Long tenantId, Long organizationId, Long storeId,
                                          boolean forceRefresh) {
        StringBuilder uri = new StringBuilder("/internal/iam/account/")
                .append(accountId).append("/permissions?tenantId=").append(tenantId);
        if (organizationId != null) {
            uri.append("&organizationId=").append(organizationId);
        }
        if (storeId != null) {
            uri.append("&storeId=").append(storeId);
        }
        if (forceRefresh) {
            uri.append("&refresh=true");
        }
        PermissionSnapshot snapshot = restClient.get()
                .uri(uri.toString())
                .retrieve()
                .body(PermissionSnapshot.class);
        return snapshot == null
                ? new PermissionSnapshot(null, null, null, null, 0, List.of())
                : snapshot;
    }

    /** 账号在某作用域下的权限快照（走 tenant 侧缓存）。 */
    public PermissionSnapshot permissions(Long accountId, Long tenantId, Long organizationId, Long storeId) {
        return permissions(accountId, tenantId, organizationId, storeId, false);
    }

    /**
     * 目标租户的币种（规范 §2/§3.1）：签发经营上下文时写进 JWT claim {@code currency}。
     *
     * <p>**失败一律回退 null（下游按 USD 处理）**：签发上下文不能因为币种查询失败而失败。
     */
    public String tenantCurrencyCode(Long tenantId) {
        if (tenantId == null) {
            return null;
        }
        try {
            TenantCurrency currency = restClient.get()
                    .uri("/internal/iam/tenant-config/{tenantId}/currency", tenantId)
                    .retrieve()
                    .body(TenantCurrency.class);
            return currency == null ? null : currency.currencyCode();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** tenant-service 内部币种契约（字段名与对外 JSON 统一为 currencyCode）。 */
    public record TenantCurrency(Long tenantId, String currencyCode) {}

    /** tenant-service 内部上下文（内部契约 DTO，非 PO）。 */
    public record TenantContext(String contextId, Long tenantId, String tenantName,
                                Long organizationId, String organizationName,
                                Long storeId, String storeName, List<String> roles, String scopeType) {}

    /** tenant-service 内部权限快照（内部契约 DTO，非 PO）。 */
    public record PermissionSnapshot(Long accountId, Long tenantId, Long organizationId, Long storeId,
                                     int authorizationVersion, List<String> permissions) {}
}
