package com.gvchat.platform.tenant.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.platform.tenant.domain.authorization.AccountContext;
import com.gvchat.platform.tenant.domain.authorization.PermissionSnapshot;
import com.gvchat.platform.tenant.domain.authorization.RoleBinding;
import com.gvchat.platform.tenant.infra.authorization.PermissionSnapshotCache;
import com.gvchat.platform.tenant.infra.persistence.mapper.IamSnapshotMapper;
import com.gvchat.platform.tenant.infra.persistence.row.IamContextRow;
import com.gvchat.platform.tenant.infra.persistence.row.IamRoleRow;
import com.gvchat.platform.tenant.infra.persistence.row.ConsumerApplicationRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IAM 权限快照提供者：按 account_id + scope 聚合 iam_user_role → iam_role → iam_permission 的权限码列表，
 * 并按账号可访问的经营上下文（tenant/organization/store）聚合 context 列表。
 *
 * <p>聚合结果经 {@link PermissionSnapshotCache} 缓存；收到 AuthorizationChanged / RoleRevoked /
 * StoreAccessChanged 事件后调用 {@link #evict(Long)} 逐出。
 */
@Service
public class PermissionSnapshotProvider {

    private final IamSnapshotMapper mapper;
    private final PermissionSnapshotCache cache;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PermissionSnapshotProvider(IamSnapshotMapper mapper, PermissionSnapshotCache cache) {
        this.mapper = mapper;
        this.cache = cache;
    }

    public AccountContext consumerContext(String appId, Long accountId) {
        ConsumerApplicationRow row = mapper.selectConsumerApplication(appId, accountId);
        if (row == null) return null;
        return new AccountContext("consumer:" + row.getAppId() + ":" + row.getTenantId() + ":"
                + (row.getOrganizationId() == null ? "" : row.getOrganizationId()) + ":"
                + (row.getStoreId() == null ? "" : row.getStoreId()), row.getTenantId(), row.getTenantName(),
                row.getOrganizationId(), row.getOrganizationName(), row.getStoreId(), row.getStoreName(),
                List.of("CONSUMER"), "CONSUMER");
    }

    public PermissionSnapshot consumerSnapshot(String appId, Long accountId) {
        ConsumerApplicationRow row = mapper.selectConsumerApplication(appId, accountId);
        if (row == null) return new PermissionSnapshot(accountId, null, null, null, 0, List.of());
        try {
            List<String> permissions = objectMapper.readValue(row.getPermissionsJson(), new TypeReference<>() {});
            return new PermissionSnapshot(accountId, row.getTenantId(), row.getOrganizationId(), row.getStoreId(),
                    row.getAuthorizationVersion() == null ? 0 : row.getAuthorizationVersion(), permissions);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid consumer application permissions", exception);
        }
    }

    public PermissionSnapshot authorizedConsumerSnapshot(String appId, Long accountId) {
        return consumerSnapshot(appId, accountId);
    }

    public void grantConsumerAuthorization(String appId, Long accountId, String scope) {
        if (appId == null || appId.isBlank() || accountId == null) {
            throw new IllegalArgumentException("appId and accountId are required");
        }
        // MySQL reports 1 row for the initial insert and 2 rows for an ON DUPLICATE KEY UPDATE.
        // Both mean the authorization is now active; only zero indicates that the app is unavailable.
        if (mapper.grantConsumerAuthorization(appId, accountId, scope == null ? "profile.basic" : scope) <= 0) {
            throw new IllegalArgumentException("consumer application is unavailable");
        }
    }

    /** 返回账号在某作用域下的权限快照（权限码 + 授权版本）。 */
    public PermissionSnapshot snapshot(Long accountId, Long tenantId, Long organizationId, Long storeId) {
        return snapshot(accountId, tenantId, organizationId, storeId, false);
    }

    /**
     * 权限快照；{@code forceRefresh=true} 时跳过缓存重新聚合。
     * 用户**主动选择/切换上下文**时走 forceRefresh：刚调整过的角色权限立即可用，
     * 不会因为 5 分钟缓存而出现「权限改了但后台仍报缺少权限」。
     */
    public PermissionSnapshot snapshot(Long accountId, Long tenantId, Long organizationId, Long storeId,
                                       boolean forceRefresh) {
        if (accountId == null || tenantId == null) {
            return new PermissionSnapshot(accountId, tenantId, organizationId, storeId, 0, List.of());
        }
        String key = cacheKey(accountId, tenantId, organizationId, storeId);
        if (!forceRefresh) {
            PermissionSnapshot cached = cache.getIfPresent(key);
            if (cached != null) {
                return cached;
            }
        }

        List<String> codes = mapper.selectPermissionCodes(accountId, tenantId, organizationId, storeId);
        Integer version = mapper.selectAuthorizationVersion(accountId, tenantId, organizationId, storeId);
        PermissionSnapshot snapshot = new PermissionSnapshot(accountId, tenantId, organizationId, storeId,
                version == null ? 0 : version, codes == null ? List.of() : codes);
        cache.put(key, snapshot);
        return snapshot;
    }

    /** 脱敏权限管理：预置角色及其 member.pii.view 状态（跨租户只读）。 */
    public List<com.gvchat.platform.tenant.infra.persistence.row.MaskingRoleRow> maskingRoles() {
        return mapper.selectMaskingRoles();
    }

    /** 账号角色绑定视图（角色名 + 作用域 + 门店名，供运营人员列表展示）。 */
    public List<RoleBinding> roleBindings(Long accountId) {
        if (accountId == null) {
            return List.of();
        }
        List<com.gvchat.platform.tenant.infra.persistence.row.IamRoleBindingRow> rows = mapper.selectRoleBindings(accountId);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<RoleBinding> result = new ArrayList<>();
        for (com.gvchat.platform.tenant.infra.persistence.row.IamRoleBindingRow row : rows) {
            result.add(new RoleBinding(row.getRoleId(), row.getTenantId(), row.getOrganizationId(), row.getStoreId(), row.getRoleCode(), row.getRoleName(),
                    row.getScopeType(), row.getTenantName(), row.getStoreName(), row.getStatus()));
        }
        return result;
    }

    /** 账号的 ACTIVE iam_user_role id 列表（删除运营人员时撤销用）。 */
    public List<Long> userRoleIds(Long accountId) {
        if (accountId == null) {
            return List.of();
        }
        List<Long> ids = mapper.selectUserRoleIds(accountId);
        return ids == null ? List.of() : ids;
    }

    /** 撤销角色绑定（软删 status → REVOKED），返回 account_id 供调用方逐出缓存/审计。 */
    public Long revokeUserRole(Long userRoleId) {
        Long accountId = mapper.selectAccountIdByUserRole(userRoleId);
        if (accountId == null) {
            return null;
        }
        mapper.revokeUserRole(userRoleId);
        evict(accountId);
        return accountId;
    }

    /** 返回账号可访问的经营上下文列表（按 tenant/organization/store 聚合角色）。 */
    public List<AccountContext> contexts(Long accountId) {
        if (accountId == null) {
            return List.of();
        }
        if (mapper.countPlatformRoles(accountId) > 0) {
            // 平台管理员：可访问全部活跃租户，角色为平台级角色；同时逐个展开租户下的启用门店，
            // 使平台运营进入租户后能选择门店上下文（库存/开台/订单等门店维度接口需要 storeId）。
            List<String> platformRoles = platformRoleCodes(accountId);
            List<AccountContext> platformContexts = new ArrayList<>();
            for (IamContextRow row : mapper.selectAllTenantContexts()) {
                platformContexts.add(toContext(row, platformRoles, row.getScopeType()));
                for (IamContextRow store : activeStores(row.getTenantId(), row.getOrganizationId())) {
                    platformContexts.add(toContext(store, platformRoles, row.getScopeType()));
                }
            }
            return platformContexts;
        }
        List<IamContextRow> contextRows = mapper.selectContexts(accountId);
        if (contextRows == null || contextRows.isEmpty()) {
            return List.of();
        }
        List<IamRoleRow> roleRows = mapper.selectRoles(accountId);

        Map<ScopeKey, List<String>> rolesByScope = new LinkedHashMap<>();
        if (roleRows != null) {
            for (IamRoleRow row : roleRows) {
                ScopeKey key = new ScopeKey(row.getTenantId(), row.getOrganizationId(), row.getStoreId());
                rolesByScope.computeIfAbsent(key, k -> new ArrayList<>()).add(row.getRoleCode());
            }
        }

        Map<ScopeKey, AccountContext> contextsByScope = new LinkedHashMap<>();
        for (IamContextRow row : contextRows) {
            ScopeKey key = new ScopeKey(row.getTenantId(), row.getOrganizationId(), row.getStoreId());
            if (contextsByScope.containsKey(key)) {
                continue;
            }
            List<String> roles = rolesByScope.getOrDefault(key, List.of());
            contextsByScope.put(key, toContext(row, roles, row.getScopeType()));
            // 租户/组织级绑定同样覆盖其名下门店：展开门店上下文供选择，授权范围不放大。
            if (row.getStoreId() == null) {
                for (IamContextRow store : activeStores(row.getTenantId(), row.getOrganizationId())) {
                    ScopeKey storeKey = new ScopeKey(store.getTenantId(), store.getOrganizationId(), store.getStoreId());
                    if (contextsByScope.containsKey(storeKey)) {
                        continue;
                    }
                    contextsByScope.put(storeKey, toContext(store, roles, row.getScopeType()));
                }
            }
        }
        return new ArrayList<>(contextsByScope.values());
    }

    /** 组装经营上下文视图（contextId 格式 tenantId:organizationId:storeId，空段即 null）。 */
    private AccountContext toContext(IamContextRow row, List<String> roles, String scopeType) {
        return new AccountContext(contextId(row.getTenantId(), row.getOrganizationId(), row.getStoreId()),
                row.getTenantId(), row.getTenantName(),
                row.getOrganizationId(), row.getOrganizationName(),
                row.getStoreId(), row.getStoreName(),
                roles, scopeType);
    }

    /** 租户/组织下的启用门店；查询异常或空结果返回空列表，不影响原有上下文。 */
    private List<IamContextRow> activeStores(Long tenantId, Long organizationId) {
        if (tenantId == null) {
            return List.of();
        }
        List<IamContextRow> stores = mapper.selectActiveStores(tenantId, organizationId);
        return stores == null ? List.of() : stores;
    }

    /**
     * 授权变更逐出：iam_user_role 写操作处调用，按 accountId 前缀精确逐出。
     */
    public void evict(Long accountId) {
        cache.evictByPrefix(accountId + ":");
    }

    /**
     * 角色-权限变更逐出：iam_role_permission 写操作处调用。
     * 当前为全量逐出；生产优化方向是按受影响角色反向定位账号后精确逐出。
     */
    public void evictAll() {
        cache.evictAll();
    }

    private List<String> platformRoleCodes(Long accountId) {
        List<IamRoleRow> roles = mapper.selectRoles(accountId);
        if (roles == null) return List.of();
        return roles.stream()
                .filter(r -> r.getTenantId() == null)
                .map(IamRoleRow::getRoleCode)
                .distinct()
                .toList();
    }

    private String cacheKey(Long accountId, Long tenantId, Long organizationId, Long storeId) {
        return accountId + ":" + contextId(tenantId, organizationId, storeId);
    }

    private String contextId(Long tenantId, Long organizationId, Long storeId) {
        return tenantId + ":" + (organizationId == null ? "" : organizationId) + ":" + (storeId == null ? "" : storeId);
    }

    private record ScopeKey(Long tenantId, Long organizationId, Long storeId) {}
}
