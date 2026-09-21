package com.gvchat.platform.tenant.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.platform.tenant.domain.authorization.AccountContext;
import com.gvchat.platform.tenant.domain.authorization.PermissionSnapshot;
import com.gvchat.platform.tenant.infra.authorization.PermissionSnapshotCache;
import com.gvchat.platform.tenant.infra.persistence.mapper.IamSnapshotMapper;
import com.gvchat.platform.tenant.infra.persistence.row.IamContextRow;
import com.gvchat.platform.tenant.infra.persistence.row.IamRoleRow;
import com.gvchat.platform.tenant.infra.persistence.row.ConsumerApplicationRow;
import java.util.List;
import org.junit.jupiter.api.Test;

/** IAM 权限快照：按 account + scope（tenant/organization/store）聚合角色、过滤跨租户/跨门店数据。 */
class PermissionSnapshotProviderTest {

  private final IamSnapshotMapper mapper = mock(IamSnapshotMapper.class);
  private final PermissionSnapshotCache cache = mock(PermissionSnapshotCache.class);
  private final PermissionSnapshotProvider provider = new PermissionSnapshotProvider(mapper, cache);

  @Test
  void snapshot_returnsEmptyWhenAccountOrTenantMissing() {
    assertEmpty(provider.snapshot(null, 1L, 2L, 3L));
    assertEmpty(provider.snapshot(1L, null, 2L, 3L));
    verify(mapper, never()).selectPermissionCodes(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  void snapshot_returnsCachedWhenPresent() {
    PermissionSnapshot cached = new PermissionSnapshot(100L, 1L, 10L, 1001L, 7, List.of("a"));
    when(cache.getIfPresent("100:1:10:1001")).thenReturn(cached);

    PermissionSnapshot result = provider.snapshot(100L, 1L, 10L, 1001L);

    assertEquals(cached, result);
    verify(mapper, never()).selectPermissionCodes(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  void snapshot_aggregatesCodesAndVersionOnMiss() {
    when(cache.getIfPresent("100:1:10:1001")).thenReturn(null);
    when(mapper.selectPermissionCodes(100L, 1L, 10L, 1001L)).thenReturn(List.of("a", "b"));
    when(mapper.selectAuthorizationVersion(100L, 1L, 10L, 1001L)).thenReturn(42);

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
    when(mapper.selectPermissionCodes(100L, 1L, 10L, 1001L)).thenReturn(null);
    when(mapper.selectAuthorizationVersion(100L, 1L, 10L, 1001L)).thenReturn(null);

    PermissionSnapshot result = provider.snapshot(100L, 1L, 10L, 1001L);

    assertEquals(0, result.authorizationVersion());
    assertIterableEquals(List.of(), result.permissions());
  }

  @Test
  void contexts_aggregatesRolesPerScopeAndIsolatesTenants() {
    when(mapper.selectContexts(100L)).thenReturn(List.of(
        ctx(1L, "Tenant A", 10L, "Org A", 1001L, "Store A1", "STORE"),
        ctx(1L, "Tenant A", 10L, "Org A", 1002L, "Store A2", "STORE"),
        ctx(2L, "Tenant B", null, null, null, null, "TENANT")));
    when(mapper.selectRoles(100L)).thenReturn(List.of(
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
    when(mapper.selectContexts(100L)).thenReturn(List.of(
        ctx(1L, "Tenant A", 10L, "Org A", 1001L, "Store A1", "STORE"),
        ctx(1L, "Tenant A", 10L, "Org A", 1001L, "Store A1", "STORE")));
    when(mapper.selectRoles(100L)).thenReturn(List.of(role(1L, 10L, 1001L, "STORE_MANAGER")));

    List<AccountContext> result = provider.contexts(100L);

    assertEquals(1, result.size());
    assertEquals("1:10:1001", result.get(0).contextId());
  }

  @Test
  void contexts_returnsEmptyForNullAccount() {
    assertIterableEquals(List.of(), provider.contexts(null));
    verify(mapper, never()).selectContexts(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void contexts_returnsEmptyWhenNoContextRows() {
    when(mapper.selectContexts(100L)).thenReturn(List.of());

    assertIterableEquals(List.of(), provider.contexts(100L));
    verify(mapper, never()).selectRoles(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void contexts_keepsContextWithEmptyRolesWhenRoleRowsNull() {
    when(mapper.selectContexts(100L)).thenReturn(List.of(
        ctx(1L, "Tenant A", 10L, "Org A", 1001L, "Store A1", "STORE")));
    when(mapper.selectRoles(100L)).thenReturn(null);

    List<AccountContext> result = provider.contexts(100L);

    assertEquals(1, result.size());
    assertIterableEquals(List.of(), result.get(0).roles());
  }

  @Test
  void consumerContext_readsRegisteredScopeAndPermissions() {
    ConsumerApplicationRow row = new ConsumerApplicationRow();
    row.setAppId("saas-a380-c");
    row.setTenantId(100L);
    row.setTenantName("A380");
    row.setOrganizationId(100L);
    row.setOrganizationName("总部");
    row.setStoreId(100L);
    row.setStoreName("首店");
    row.setPermissionsJson("[\"reservation.view\",\"reservation.create\"]");
    row.setAuthorizationVersion(1);
    when(mapper.selectConsumerApplication("saas-a380-c", 158L)).thenReturn(row);

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
    verify(mapper).selectConsumerApplication("saas-a380-c", 159L);
  }

  private static IamContextRow ctx(Long tenantId, String tenantName, Long orgId, String orgName,
      Long storeId, String storeName, String scopeType) {
    IamContextRow row = new IamContextRow();
    row.setTenantId(tenantId);
    row.setTenantName(tenantName);
    row.setOrganizationId(orgId);
    row.setOrganizationName(orgName);
    row.setStoreId(storeId);
    row.setStoreName(storeName);
    row.setScopeType(scopeType);
    return row;
  }

  private static IamRoleRow role(Long tenantId, Long orgId, Long storeId, String roleCode) {
    IamRoleRow row = new IamRoleRow();
    row.setTenantId(tenantId);
    row.setOrganizationId(orgId);
    row.setStoreId(storeId);
    row.setRoleCode(roleCode);
    return row;
  }

  private static void assertEmpty(PermissionSnapshot snapshot) {
    assertNotNull(snapshot);
    assertEquals(0, snapshot.authorizationVersion());
    assertIterableEquals(List.of(), snapshot.permissions());
  }
}
