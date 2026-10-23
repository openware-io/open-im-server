package io.openware.platform.tenant.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.platform.tenant.domain.authorization.AccountContext;
import io.openware.platform.tenant.domain.authorization.ConsumerAuthorization;
import io.openware.platform.tenant.domain.authorization.IamContext;
import io.openware.platform.tenant.domain.authorization.PermissionSnapshot;
import io.openware.platform.tenant.domain.authorization.RoleBinding;
import io.openware.platform.tenant.domain.authorization.RoleScope;
import io.openware.platform.tenant.domain.authorization.repository.IamAuthorizationRepository;
import io.openware.platform.tenant.domain.iam.model.MaskingRole;
import io.openware.platform.tenant.infra.authorization.PermissionSnapshotCache;
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
 *
 * <p>事实读取经 {@link IamAuthorizationRepository}：本类只做**装配与缓存**，不认识持久化行对象。
 * 这一层刻意保留在应用层——它是有缓存的读模型装配（跨用例复用），不是无状态适配器。
 */
@Service
public class PermissionSnapshotProvider {

    private final IamAuthorizationRepository authorizationRepository;
    private final PermissionSnapshotCache cache;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PermissionSnapshotProvider(IamAuthorizationRepository authorizationRepository,
                                      PermissionSnapshotCache cache) {
        this.authorizationRepository = authorizationRepository;
        this.cache = cache;
    }

    public AccountContext consumerContext(String appId, Long accountId) {
        ConsumerAuthorization row = authorizationRepository.findConsumerAuthorization(appId, accountId)
                .orElse(null);
        if (row == null) {
            return null;
        }
        return new AccountContext("consumer:" + row.appId() + ":" + row.tenantId() + ":"
                + (row.organizationId() == null ? "" : row.organizationId()) + ":"
                + (row.storeId() == null ? "" : row.storeId()), row.tenantId(), row.tenantName(),
                row.organizationId(), row.organizationName(), row.storeId(), row.storeName(),
                List.of("CONSUMER"), "CONSUMER");
    }

    public PermissionSnapshot consumerSnapshot(String appId, Long accountId) {
        ConsumerAuthorization row = authorizationRepository.findConsumerAuthorization(appId, accountId)
                .orElse(null);
        if (row == null) {
            return new PermissionSnapshot(accountId, null, null, null, 0, List.of());
        }
        try {
            List<String> permissions = objectMapper.readValue(row.permissionsJson(), new TypeReference<>() {});
            return new PermissionSnapshot(accountId, row.tenantId(), row.organizationId(), row.storeId(),
                    row.authorizationVersion() == null ? 0 : row.authorizationVersion(), permissions);
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
        // 端口把"首次插入 1 行 / 冲突更新 2 行 / 应用不可用 0 行"的 MySQL 行数约定收敛成布尔。
        if (!authorizationRepository.grantConsumerAuthorization(appId, accountId,
                scope == null ? "profile.basic" : scope)) {
            throw new IllegalArgumentException("consumer application is unavailable");
        }
    }

    public void revokeConsumerAuthorization(String appId, Long accountId) {
        if (appId == null || appId.isBlank() || accountId == null) {
            throw new IllegalArgumentException("appId and accountId are required");
        }
        authorizationRepository.revokeConsumerAuthorization(appId, accountId);
        evict(accountId);
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

        List<String> codes = authorizationRepository.findPermissionCodes(accountId, tenantId,
                organizationId, storeId);
        Integer version = authorizationRepository.findAuthorizationVersion(accountId, tenantId,
                organizationId, storeId).orElse(0);
        PermissionSnapshot snapshot = new PermissionSnapshot(accountId, tenantId, organizationId, storeId,
                version, codes);
        cache.put(key, snapshot);
        return snapshot;
    }

    /** 脱敏权限管理：预置角色及其 member.pii.view 状态（跨租户只读）。 */
    public List<MaskingRole> maskingRoles() {
        return authorizationRepository.findMaskingRoles();
    }

    /** 账号角色绑定视图（角色名 + 作用域 + 门店名，供运营人员列表展示）。 */
    public List<RoleBinding> roleBindings(Long accountId) {
        return accountId == null ? List.of() : authorizationRepository.findRoleBindings(accountId);
    }

    /** 账号的 ACTIVE iam_user_role id 列表（删除运营人员时撤销用）。 */
    public List<Long> userRoleIds(Long accountId) {
        return accountId == null ? List.of() : authorizationRepository.findUserRoleIds(accountId);
    }

    /**
     * 撤销角色绑定（软删 status → REVOKED），返回 account_id 供调用方逐出缓存/审计。
     *
     * <p>返回 {@code null} 表示绑定不存在，调用方据此回 404 而不是静默成功。
     */
    public Long revokeUserRole(Long userRoleId) {
        Long accountId = authorizationRepository.findAccountIdByUserRole(userRoleId).orElse(null);
        if (accountId == null) {
            return null;
        }
        authorizationRepository.revokeUserRole(userRoleId);
        evict(accountId);
        return accountId;
    }

    /** 返回账号可访问的经营上下文列表（按 tenant/organization/store 聚合角色）。 */
    public List<AccountContext> contexts(Long accountId) {
        if (accountId == null) {
            return List.of();
        }
        if (authorizationRepository.countPlatformRoles(accountId) > 0) {
            // 平台管理员：可访问全部活跃租户，角色为平台级角色；同时逐个展开租户下的启用门店，
            // 使平台运营进入租户后能选择门店上下文（库存/开台/订单等门店维度接口需要 storeId）。
            List<String> platformRoles = platformRoleCodes(accountId);
            List<AccountContext> platformContexts = new ArrayList<>();
            for (IamContext row : authorizationRepository.findAllTenantContexts()) {
                platformContexts.add(toContext(row, platformRoles, row.scopeType()));
                for (IamContext store : activeStores(row.tenantId(), row.organizationId())) {
                    platformContexts.add(toContext(store, platformRoles, row.scopeType()));
                }
            }
            return platformContexts;
        }
        List<IamContext> contextRows = authorizationRepository.findContexts(accountId);
        if (contextRows.isEmpty()) {
            return List.of();
        }
        List<RoleScope> roleRows = authorizationRepository.findRoleScopes(accountId);

        Map<ScopeKey, List<String>> rolesByScope = new LinkedHashMap<>();
        for (RoleScope row : roleRows) {
            ScopeKey key = new ScopeKey(row.tenantId(), row.organizationId(), row.storeId());
            rolesByScope.computeIfAbsent(key, k -> new ArrayList<>()).add(row.roleCode());
        }

        Map<ScopeKey, AccountContext> contextsByScope = new LinkedHashMap<>();
        for (IamContext row : contextRows) {
            ScopeKey key = new ScopeKey(row.tenantId(), row.organizationId(), row.storeId());
            if (contextsByScope.containsKey(key)) {
                continue;
            }
            List<String> roles = rolesByScope.getOrDefault(key, List.of());
            contextsByScope.put(key, toContext(row, roles, row.scopeType()));
            // 租户/组织级绑定同样覆盖其名下门店：展开门店上下文供选择，授权范围不放大。
            if (row.storeId() == null) {
                for (IamContext store : activeStores(row.tenantId(), row.organizationId())) {
                    ScopeKey storeKey = new ScopeKey(store.tenantId(), store.organizationId(),
                            store.storeId());
                    if (contextsByScope.containsKey(storeKey)) {
                        continue;
                    }
                    contextsByScope.put(storeKey, toContext(store, roles, row.scopeType()));
                }
            }
        }
        return new ArrayList<>(contextsByScope.values());
    }

    /** 组装经营上下文视图（contextId 格式 tenantId:organizationId:storeId，空段即 null）。 */
    private AccountContext toContext(IamContext row, List<String> roles, String scopeType) {
        return new AccountContext(contextId(row.tenantId(), row.organizationId(), row.storeId()),
                row.tenantId(), row.tenantName(),
                row.organizationId(), row.organizationName(),
                row.storeId(), row.storeName(),
                roles, scopeType, row.businessType(), row.timezone());
    }

    /** 租户/组织下的启用门店；查询异常或空结果返回空列表，不影响原有上下文。 */
    private List<IamContext> activeStores(Long tenantId, Long organizationId) {
        if (tenantId == null) {
            return List.of();
        }
        return authorizationRepository.findActiveStores(tenantId, organizationId);
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
        return authorizationRepository.findRoleScopes(accountId).stream()
                .filter(r -> r.tenantId() == null)
                .map(RoleScope::roleCode)
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
