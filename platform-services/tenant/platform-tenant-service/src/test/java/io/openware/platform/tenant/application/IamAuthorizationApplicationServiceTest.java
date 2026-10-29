package io.openware.platform.tenant.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.domain.iam.model.Permission;
import io.openware.platform.tenant.domain.iam.model.Role;
import io.openware.platform.tenant.domain.iam.repository.IamCatalogVersionRepository;
import io.openware.platform.tenant.domain.iam.repository.PermissionRepository;
import io.openware.platform.tenant.domain.iam.repository.RoleGrantRepository;
import io.openware.platform.tenant.domain.iam.repository.RoleRepository;
import io.openware.platform.tenant.domain.iam.repository.UserRoleRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 角色权限编辑的乐观并发：版本不匹配拒绝写入、匹配才替换、快照带版本。 */
class IamAuthorizationApplicationServiceTest {

  private final RoleRepository roles = mock(RoleRepository.class);
  private final PermissionRepository permissions = mock(PermissionRepository.class);
  private final RoleGrantRepository grants = mock(RoleGrantRepository.class);
  private final UserRoleRepository userRoles = mock(UserRoleRepository.class);
  private final PermissionSnapshotProvider snapshots = mock(PermissionSnapshotProvider.class);
  private final IamCatalogVersionRepository versions = mock(IamCatalogVersionRepository.class);

  private final IamAuthorizationApplicationService service = new IamAuthorizationApplicationService(
      roles, permissions, grants, userRoles, snapshots, versions);

  @BeforeEach
  void enterTenantContext() {
    TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1, List.of(), "TENANT", null, null));
  }

  @AfterEach
  void clearContext() {
    TenantContextHolder.clear();
  }

  @Test
  void permissionCountsUsesBatchRepository() {
    when(grants.countByRoleIds(List.of(7L, 8L))).thenReturn(Map.of(7L, 3L));

    Map<Long, Long> counts = service.permissionCounts(List.of(7L, 8L));

    assertEquals(Map.of(7L, 3L), counts);
    verify(grants).countByRoleIds(List.of(7L, 8L));
  }

  @Test
  void staleVersionIsRejectedBeforeReplace() {
    when(roles.findByIdForUpdate(7L)).thenReturn(Optional.of(tenantRole()));
    when(versions.rolePermissionVersion(7L)).thenReturn("v2");

    ApiException error = assertThrows(ApiException.class,
        () -> service.assignPermissions(7L, List.of(), "v1"));

    assertEquals("PERMISSION_VERSION_CONFLICT", error.getCode());
    assertEquals(409, error.getStatus());
    verify(grants, never()).replaceRolePermissions(anyLong(), anyList());
    verify(snapshots, never()).evictAll();
  }

  @Test
  void matchingVersionReplacesAndEvictsSnapshot() {
    when(roles.findByIdForUpdate(7L)).thenReturn(Optional.of(tenantRole()));
    when(versions.rolePermissionVersion(7L)).thenReturn("v1");

    Role result = service.assignPermissions(7L, List.of(), "v1");

    assertEquals(7L, result.id());
    verify(grants).replaceRolePermissions(7L, List.of());
    verify(snapshots).evictAll();
  }

  @Test
  void legacyCallWithoutVersionStillWrites() {
    when(roles.findByIdForUpdate(7L)).thenReturn(Optional.of(tenantRole()));

    service.assignPermissions(7L, List.of());

    verify(grants).replaceRolePermissions(7L, List.of());
    verify(versions, never()).rolePermissionVersion(anyLong());
  }

  @Test
  void snapshotReturnsCodesAndVersion() {
    when(roles.findById(7L)).thenReturn(Optional.of(tenantRole()));
    when(grants.findPermissionIdsByRole(7L)).thenReturn(List.of(1L));
    when(permissions.findByIds(List.of(1L))).thenReturn(List.of(
        new Permission(1L, "tenant.store.manage", "tenant", "store", "manage", "门店管理",
            "TENANT", "core", "TENANT", "store", Permission.STATUS_ACTIVE)));
    when(versions.rolePermissionVersion(7L)).thenReturn("v1");

    IamAuthorizationApplicationService.RolePermissionSnapshot snapshot =
        service.rolePermissionSnapshot(7L);

    assertEquals(List.of("tenant.store.manage"), snapshot.permissionCodes());
    assertEquals("v1", snapshot.version());
  }

  @Test
  void snapshotForUnknownRoleIsNotFound() {
    when(roles.findById(404L)).thenReturn(Optional.empty());

    ApiException error = assertThrows(ApiException.class, () -> service.rolePermissionSnapshot(404L));

    assertEquals("ROLE_NOT_FOUND", error.getCode());
  }

  @Test
  void otherTenantRolePermissionsCannotBeRead() {
    when(roles.findById(8L)).thenReturn(Optional.of(new Role(8L, 200L, "other.custom", "其他租户角色",
        Role.TYPE_TENANT, "TENANT", null, null, Role.STATUS_ACTIVE)));

    ApiException error = assertThrows(ApiException.class, () -> service.listRolePermissionCodes(8L));

    assertEquals("ROLE_TENANT_MISMATCH", error.getCode());
    verify(grants, never()).findPermissionIdsByRole(8L);
  }

  private static Role tenantRole() {
    return new Role(7L, 100L, "tenant.custom", "自定义角色", Role.TYPE_TENANT, "TENANT",
        null, null, Role.STATUS_ACTIVE);
  }
}
