package io.openware.platform.tenant.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.platform.tenant.domain.authorization.AccountContext;
import io.openware.platform.tenant.domain.authorization.PermissionSnapshot;
import io.openware.platform.tenant.infra.authorization.PermissionSnapshotCache;
import io.openware.platform.tenant.domain.authorization.ConsumerAuthorization;
import io.openware.platform.tenant.domain.authorization.IamContext;
import io.openware.platform.tenant.domain.authorization.RoleScope;
import io.openware.platform.tenant.domain.authorization.repository.IamAuthorizationRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** IAM 权限快照：按 account + scope（tenant/organization/store）聚合角色、过滤跨租户/跨门店数据。 */
class PermissionSnapshotProviderTest {

  private final IamAuthorizationRepository authorization = mock(IamAuthorizationRepository.class);
  private final PermissionSnapshotCache cache = mock(PermissionSnapshotCache.class);
  private final PermissionSnapshotProvider provider = new PermissionSnapshotProvider(authorization, cache);

  @Test
  void snapshot_returnsEmptyWhenAccountOrTenantMissing() {
    assertEmpty(provider.snapshot(null, 1L, 2L, 3L));
    assertEmpty(provider.snapshot(1L, null, 2L, 3L));
    verify(authorization, never()).findPermissionCodes(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  void snapshot_returnsCachedWhenPresent() {
    PermissionSnapshot cached = new PermissionSnapshot(100L, 1L, 10L, 1001L, 7, List.of("a"));
    when(cache.getIfPresent("100:1:10:1001")).thenReturn(cached);

    PermissionSnapshot result = provider.snapshot(100L, 1L, 10L, 1001L);

    assertEquals(cached, result);
    verify(authorization, never()).findPermissionCodes(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  void snapshot_aggregatesCodesAndVersionOnMiss() {
    when(cache.getIfPresent("100:1:10:1001")).thenReturn(null);
    when(authorization.findPermissionCodes(100L, 1L, 10L, 1001L)).thenReturn(List.of("a", "b"));
    when(authorization.findAuthorizationVersion(100L, 1L, 10L, 1001L)).thenReturn(java.util.Optional.of(42));

    PermissionSnapshot result = provider.snapshot(100L, 1L, 10L, 1001L);

    assertEquals(100L, result.accountId());
    assertEquals(1L, result.tenantId());
    assertEquals(10L, result.organizationId());
    assertEquals(1001L, result.storeId());
    assertEquals(42, result.authorizationVersion());
    assertIterableEquals(List.of("a", "b"), result.permissions());
    verify(cache).put("100:1:10:1001", result);
  }

  @Test
  void snapshot_treatsNullVersionAsZero() {
    when(cache.getIfPresent("100:1:10:1001")).thenReturn(null);
    // 端口契约：无权限时返回空列表、无版本时返回空 Optional——null 已在适配器里收敛掉，
    // 因此"少权限/少版本"表现为空值而不是 null。
    when(authorization.findPermissionCodes(100L, 1L, 10L, 1001L)).thenReturn(List.of());
    when(authorization.findAuthorizationVersion(100L, 1L, 10L, 1001L)).thenReturn(java.util.Optional.empty());

    PermissionSnapshot result = provider.snapshot(100L, 1L, 10L, 1001L);

    assertEquals(0, result.authorizationVersion());
    assertIterableEquals(List.of(), result.permissions());
  }

  @Test
  void contexts_aggregatesRolesPerScopeAndIsolatesTenants() {
    when(authorization.findContexts(100L)).thenReturn(List.of(
        ctx(1L, "Tenant A", 10L, "Org A", 1001L, "Store A1", "STORE"),
        ctx(1L, "Tenant A", 10L, "Org A", 1002L, "Store A2", "STORE"),
        ctx(2L, "Tenant B", null, null, null, null, "TENANT")));
    when(authorization.findRoleScopes(100L)).thenReturn(List.of(
        role(1L, 10L, 1001L, "STORE_MANAGER"),
        role(1L, 10L, 1001L, "CASHIER"),
        role(1L, 10L, 1002L, "STORE_MANAGER"),
        role(2L, null, null, "TENANT_ADMIN"),
        role(3L, null, null, "ORPHAN_ROLE")));

    List<AccountContext> result = provider.contexts(100L);

    assertIterableEquals(List.of(
        new AccountContext("1:10:1001", 1L, "Tenant A", 10L, "Org A", 1001L, "Store A1",
            List.of("STORE_MANAGER", "CASHIER"), "STORE"),
        new AccountContext("1:10:1002", 1L, "Tenant A", 10L, "Org A", 1002L, "Store A2",
            List.of("STORE_MANAGER"), "STORE"),
        new AccountContext("2::", 2L, "Tenant B", null, null, null, null,
            List.of("TENANT_ADMIN"), "TENANT")), result);
  }

  @Test
  void contexts_deduplicatesByScope() {
    when(authorization.findContexts(100L)).thenReturn(List.of(
        ctx(1L, "Tenant A", 10L, "Org A", 1001L, "Store A1", "STORE"),
        ctx(1L, "Tenant A", 10L, "Org A", 1001L, "Store A1", "STORE")));
    when(authorization.findRoleScopes(100L)).thenReturn(List.of(role(1L, 10L, 1001L, "STORE_MANAGER")));

    List<AccountContext> result = provider.contexts(100L);

    assertEquals(1, result.size());
    assertEquals("1:10:1001", result.get(0).contextId());
  }

  @Test
  void contexts_returnsEmptyForNullAccount() {
    assertIterableEquals(List.of(), provider.contexts(null));
    verify(authorization, never()).findContexts(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void contexts_returnsEmptyWhenNoContextRows() {
    when(authorization.findContexts(100L)).thenReturn(List.of());

    assertIterableEquals(List.of(), provider.contexts(100L));
    verify(authorization, never()).findRoleScopes(org.mockito.ArgumentMatchers.any());
  }

  /**
   * 上下文没有任何角色绑定时仍要保留（账号能进这个租户/门店，只是尚未被授予角色）。
   *
   * <p>端口契约是无角色时返回空列表而不是 null——null 会在装配时炸掉整条上下文查询，
   * 让"还没配角色"变成一个 500。
   */
  @Test
  void contexts_keepsContextWithEmptyRolesWhenNoRoleBindings() {
    when(authorization.findContexts(100L)).thenReturn(List.of(
        ctx(1L, "Tenant A", 10L, "Org A", 1001L, "Store A1", "STORE")));
    when(authorization.findRoleScopes(100L)).thenReturn(List.of());

    List<AccountContext> result = provider.contexts(100L);

    assertEquals(1, result.size());
    assertIterableEquals(List.of(), result.get(0).roles());
  }

  @Test
  void consumerContext_readsRegisteredScopeAndPermissions() {
    ConsumerAuthorization row = new ConsumerAuthorization("saas-a380-c", 100L, "A380", 100L, "总部", 100L,
        "首店", "[\"reservation.view\",\"reservation.create\"]", 1);
    when(authorization.findConsumerAuthorization("saas-a380-c", 158L)).thenReturn(Optional.of(row));

    AccountContext context = provider.consumerContext("saas-a380-c", 158L);
    PermissionSnapshot snapshot = provider.consumerSnapshot("saas-a380-c", 158L);

    assertEquals("consumer:saas-a380-c:100:100:100", context.contextId());
    assertEquals(158L, snapshot.accountId());
    assertIterableEquals(List.of("reservation.view", "reservation.create"), snapshot.permissions());
  }

  @Test
  void authorizedConsumerSnapshot_returnsEmptyWhenAccountHasNoActiveGrant() {
    PermissionSnapshot result = provider.authorizedConsumerSnapshot("saas-a380-c", 159L);

    assertEmpty(result);
    verify(authorization).findConsumerAuthorization("saas-a380-c", 159L);
  }

  private static IamContext ctx(Long tenantId, String tenantName, Long orgId, String orgName,
      Long storeId, String storeName, String scopeType) {
    return new IamContext(tenantId, tenantName, orgId, orgName, storeId, storeName, scopeType, null, null);
  }

  private static RoleScope role(Long tenantId, Long orgId, Long storeId, String roleCode) {
    return new RoleScope(tenantId, orgId, storeId, roleCode);
  }

  private static void assertEmpty(PermissionSnapshot snapshot) {
    assertNotNull(snapshot);
    assertEquals(0, snapshot.authorizationVersion());
    assertIterableEquals(List.of(), snapshot.permissions());
  }
}
