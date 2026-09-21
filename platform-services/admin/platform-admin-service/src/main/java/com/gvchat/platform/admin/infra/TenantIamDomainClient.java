package com.gvchat.platform.admin.infra;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.infrastructure.security.InternalServiceAuthenticationInterceptor;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.platform.admin.infra.security.AdminContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * tenant-service 内部 IAM 端点客户端（RestClient）：经营上下文 + 权限快照。
 * 供 SaaS 后台租户上下文选择 BFF 使用，base-url 来自环境变量 TENANT_SERVICE_BASE_URL。
 * 注意：响应统一按 String 取回再用 Jackson 2 的 ObjectMapper 解析（与 ReservationDomainClient 一致），
 * 避免 Spring Boot 4 消息转换器对 com.fasterxml.jackson.databind.JsonNode 的反序列化问题。
 */
@Component
public class TenantIamDomainClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TenantIamDomainClient(@Value("${TENANT_SERVICE_BASE_URL:http://platform-tenant-service:4110}") String baseUrl,
                                 InternalServiceAuthenticationInterceptor internalAuthInterceptor) {
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory)
                .requestInterceptor(internalAuthInterceptor).build();
    }

    /** 账号可访问的经营上下文列表。 */
    public List<Context> contexts(Long accountId) {
        try {
            String resp = restClient.get()
                    .uri("/internal/iam/account/{accountId}/contexts", accountId)
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(resp);
            List<Context> result = new ArrayList<>();
            if (node != null && node.isArray()) {
                for (JsonNode item : node) {
                    result.add(new Context(
                            item.path("contextId").asText(),
                            item.path("tenantId").asLong(),
                            text(item, "tenantName"),
                            nullableLong(item, "organizationId"),
                            text(item, "organizationName"),
                            nullableLong(item, "storeId"),
                            text(item, "storeName"),
                            stringList(item, "roles"),
                            text(item, "scopeType")));
                }
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("调用 tenant-service contexts 失败", e);
        }
    }

    /** 账号在某作用域下的权限快照（权限码 + 授权版本）。 */
    public PermissionSnapshot permissions(Long accountId, Long tenantId, Long organizationId, Long storeId) {
        return permissions(accountId, tenantId, organizationId, storeId, false);
    }

    /**
     * 权限快照；{@code forceRefresh=true} 时跳过 tenant 侧缓存。
     * 后台选择/切换运营上下文时必须强制刷新，否则刚调整的角色权限会被 5 分钟缓存挡住，
     * 表现为「已授权但页面仍报缺少权限」。
     */
    public PermissionSnapshot permissions(Long accountId, Long tenantId, Long organizationId, Long storeId,
                                          boolean forceRefresh) {
        try {
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
            String resp = restClient.get()
                    .uri(uri.toString())
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(resp);
            return new PermissionSnapshot(
                    node.path("accountId").asLong(),
                    node.path("tenantId").asLong(),
                    nullableLong(node, "organizationId"),
                    nullableLong(node, "storeId"),
                    node.path("authorizationVersion").asInt(),
                    stringList(node, "permissions"));
        } catch (Exception e) {
            throw new IllegalStateException("调用 tenant-service permissions 失败", e);
        }
    }

    /** 账号角色绑定视图（角色名 + 作用域 + 门店名）。 */
    public List<RoleBinding> roleBindings(Long accountId) {
        try {
            String resp = restClient.get()
                    .uri("/internal/iam/account/{accountId}/role-bindings", accountId)
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(resp);
            List<RoleBinding> result = new ArrayList<>();
            if (node != null && node.isArray()) {
                for (JsonNode item : node) {
                    result.add(new RoleBinding(
                            nullableLong(item, "roleId"), nullableLong(item, "tenantId"),
                            nullableLong(item, "organizationId"), nullableLong(item, "storeId"),
                            text(item, "roleCode"),
                            text(item, "roleName"),
                            text(item, "scopeType"),
                            text(item, "tenantName"),
                            text(item, "storeName"),
                            text(item, "status")));
                }
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("调用 tenant-service role-bindings 失败", e);
        }
    }

    /**
     * 目标租户的币种（规范 §2/§3.1）：签发运营上下文时写进 JWT claim {@code currency}。
     *
     * <p>**失败一律回退 USD**：签发上下文不能因为币种查询失败而失败（老 token 语义就是 USD）。
     */
    public String tenantCurrencyCode(Long tenantId) {
        try {
            String resp = restClient.get()
                    .uri("/internal/iam/tenant-config/{tenantId}/currency", tenantId)
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(resp);
            return node == null ? null : text(node, "currencyCode");
        } catch (Exception e) {
            return null;
        }
    }

    /** 账号的 iam_user_role 绑定 id 列表（删除运营人员时逐个撤销）。 */
    public List<Long> listUserRoleIds(Long accountId) {
        try {
            String resp = restClient.get()
                    .uri("/admin/iam/accounts/{accountId}/user-roles", accountId)
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(resp);
            List<Long> ids = new ArrayList<>();
            if (node != null && node.isArray()) {
                for (JsonNode item : node) {
                    ids.add(item.asLong());
                }
            }
            return ids;
        } catch (Exception e) {
            throw new IllegalStateException("调用 tenant 服务查询角色绑定失败", e);
        }
    }

    /** 撤销账号在某作用域下的角色绑定（软删 status → REVOKED）。 */
    public void revokeUserRole(Long userRoleId) {
        try {
            restClient.delete()
                    .uri("/admin/iam/user-roles/{userRoleId}", userRoleId)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new IllegalStateException("调用 tenant 服务撤销角色失败", e);
        }
    }

    /** 给账号分配角色（租户/门店作用域），转发 tenant-service 的 /admin/iam/user-roles。 */
    public void assignUserRole(Long accountId, Long tenantId, Long organizationId, Long storeId,
                               Long roleId, String scopeType) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "accountId", accountId,
                    "tenantId", tenantId,
                    "organizationId", organizationId == null ? "" : String.valueOf(organizationId),
                    "storeId", storeId == null ? "" : String.valueOf(storeId),
                    "roleId", roleId,
                    "scopeType", scopeType));
            restClient.post()
                    .uri("/admin/iam/user-roles")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new IllegalStateException("调用 tenant 服务分配角色失败", e);
        }
    }

    private Long nullableLong(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asLong() : null;
    }

    public boolean isStaffRole(Long roleId, String scopeType) {
        return staffRoles().stream().anyMatch(role -> role.roleId().equals(roleId) && role.scopeType().equals(scopeType));
    }

    public List<StaffStore> staffStores(TenantContext context) {
        try {
            String response = restClient.get().uri("/admin/tenant/stores?status=ACTIVE")
                    .header(TenantContext.HEADER, AdminContextHolder.get().tenantContextToken()).retrieve().body(String.class);
            JsonNode stores = objectMapper.readTree(response);
            if (stores == null || !stores.isArray()) throw new IllegalStateException("Invalid store catalog");
            List<StaffStore> result = new ArrayList<>();
            for (JsonNode store : stores) {
                Long tenantId = nullableLong(store, "tenantId");
                Long organizationId = nullableLong(store, "organizationId");
                Long storeId = nullableLong(store, "id");
                if (java.util.Objects.equals(context.tenantId(), tenantId)
                        && (context.organizationId() == null || context.organizationId().equals(organizationId))
                        && (context.storeId() == null || context.storeId().equals(storeId))) {
                    result.add(new StaffStore(storeId, organizationId, text(store, "name")));
                }
            }
            return result;
        } catch (Exception exception) {
            throw new IllegalStateException("调用 tenant 服务查询可授权门店失败", exception);
        }
    }

    public List<StaffRole> staffRoles() {
        try {
            String response = restClient.get().uri("/admin/iam/masking").retrieve().body(String.class);
            JsonNode roles = objectMapper.readTree(response);
            if (roles == null || !roles.isArray()) throw new IllegalStateException("Invalid role catalog");
            List<StaffRole> result = new ArrayList<>();
            for (JsonNode role : roles) {
                String code = role.path("code").asText();
                if (List.of("tenant.owner", "store.manager", "store.cashier", "store.finance").contains(code)) {
                    result.add(new StaffRole(nullableLong(role, "roleId"), code, text(role, "name"),
                            "tenant.owner".equals(code) ? "TENANT" : "STORE"));
                }
            }
            return result;
        } catch (Exception exception) {
            throw new IllegalStateException("调用 tenant 服务查询运营角色失败", exception);
        }
    }

    public record StaffRole(Long roleId, String code, String name, String scopeType) {}
    public record StaffStore(Long id, Long organizationId, String name) {}

    private String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private List<String> stringList(JsonNode node, String field) {
        List<String> result = new ArrayList<>();
        if (node.has(field) && node.get(field).isArray()) {
            for (JsonNode item : node.get(field)) {
                result.add(item.asText());
            }
        }
        return result;
    }

    /** tenant-service 内部上下文（内部契约 DTO，非 PO）。 */
    public record Context(String contextId, Long tenantId, String tenantName,
                          Long organizationId, String organizationName,
                          Long storeId, String storeName, List<String> roles, String scopeType) {}

    /** tenant-service 内部权限快照（内部契约 DTO，非 PO）。 */
    public record PermissionSnapshot(Long accountId, Long tenantId, Long organizationId, Long storeId,
                                     int authorizationVersion, List<String> permissions) {}

    /** tenant-service 内部角色绑定视图（内部契约 DTO，非 PO）。 */
    public record RoleBinding(Long roleId, Long tenantId, Long organizationId, Long storeId, String roleCode, String roleName, String scopeType,
                              String tenantName, String storeName, String status) {}
}
