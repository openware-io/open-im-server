package io.openware.platform.tenant.api.controller;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.application.IamAuthorizationApplicationService;
import io.openware.platform.tenant.application.PermissionCatalogApplicationService;
import io.openware.platform.tenant.application.PermissionSnapshotProvider;
import io.openware.platform.tenant.application.RoleBindingApplicationService;
import io.openware.platform.tenant.domain.iam.model.Permission;
import io.openware.platform.tenant.domain.iam.model.PermissionCatalogSnapshot;
import io.openware.platform.tenant.domain.iam.model.Role;
import io.openware.platform.tenant.domain.iam.model.RoleBinding;
import io.openware.platform.tenant.domain.iam.model.UserRoleBinding;
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

/**
 * 授权边界在控制器路径上的落点：越界的授予请求必须被拒绝且不产生任何写入。
 *
 * <p>规则本身（白名单匹配、作用域与归属字段的自洽）在 {@code IamDomainRulesTest} 里穷举；这里只保证
 * 控制器路径确实把它接到写操作之前——规则正确但没被调用，等价于没有规则。
 */
class IamControllerScopeTest {
    private final RoleRepository roles = mock(RoleRepository.class);
    private final PermissionRepository permissions = mock(PermissionRepository.class);
    private final RoleGrantRepository roleGrants = mock(RoleGrantRepository.class);
    private final UserRoleRepository userRoles = mock(UserRoleRepository.class);
    private final PermissionSnapshotProvider snapshots = mock(PermissionSnapshotProvider.class);
    private final IamCatalogVersionRepository catalogVersions = mock(IamCatalogVersionRepository.class);
    private final AuditClient audit = mock(AuditClient.class);
    private final RoleBindingApplicationService roleBindings = mock(RoleBindingApplicationService.class);
    private final PermissionCatalogApplicationService permissionCatalog = mock(PermissionCatalogApplicationService.class);
    private final IamAuthorizationApplicationService authorization =
            new IamAuthorizationApplicationService(roles, permissions, roleGrants, userRoles, snapshots,
                    catalogVersions, audit);
    private final IamController controller = new IamController(authorization, roleBindings, permissionCatalog);

    @BeforeEach
    void enterTenantContext() {
        TenantContextHolder.set(new TenantContext(100L, null, null, 1L, 1,
                List.of("iam.role.manage", "iam.role.view"), "TENANT", null, null));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    void storeRoleCannotReceiveTenantOnlyPermission() {
        when(roles.findByIdForUpdate(7L)).thenReturn(Optional.of(role(7L, "STORE")));
        when(permissions.findByIds(List.of(9L)))
                .thenReturn(List.of(permission(9L, "tenant.store.manage", "TENANT")));

        ApiException error = assertThrows(ApiException.class,
                () -> controller.assignPermissions(7L, new IamController.AssignPermissionsRequest(List.of(9L))));

        assertEquals("INVALID_GRANT_SCOPE", error.getCode());
        verify(roleGrants, never()).replaceRolePermissions(any(), any());
    }

    /**
     * 请求了不存在的权限标识必须拒绝：静默忽略会让前端以为授予成功，实际少授了权限。
     */
    @Test
    void unknownPermissionIdIsRejectedNotIgnored() {
        when(roles.findByIdForUpdate(7L)).thenReturn(Optional.of(role(7L, "TENANT")));
        when(permissions.findByIds(List.of(9L, 999L)))
                .thenReturn(List.of(permission(9L, "a.b", "TENANT")));

        ApiException error = assertThrows(ApiException.class, () -> controller.assignPermissions(7L,
                new IamController.AssignPermissionsRequest(List.of(9L, 999L))));

        assertEquals("INVALID_GRANT_SCOPE", error.getCode());
        verify(roleGrants, never()).replaceRolePermissions(any(), any());
    }

    @Test
    void inactiveRoleIsNotFoundAndNothingIsWritten() {
        when(roles.findByIdForUpdate(7L)).thenReturn(Optional.empty());

        ApiException error = assertThrows(ApiException.class,
                () -> controller.assignPermissions(7L, new IamController.AssignPermissionsRequest(List.of())));

        assertEquals("ROLE_NOT_FOUND", error.getCode());
        verify(roleGrants, never()).replaceRolePermissions(any(), any());
    }

    @Test
    void listRolesRejectsOtherTenant() {
        ApiException error = assertThrows(ApiException.class, () -> controller.listRoles(200L));
        assertEquals("TENANT_SCOPE_DENIED", error.getCode());
    }

    @Test
    void invalidScopeIsRejectedByBindingService() {
        when(roleBindings.assignByAccount(10L, 100L, null, 101L, 7L, "STORE"))
                .thenThrow(new ApiException(400, "INVALID_GRANT_SCOPE", "invalid"));

        ApiException error = assertThrows(ApiException.class, () -> controller.assignUserRole(
                new IamController.AssignUserRoleRequest(10L, 100L, null, 101L, 7L, "STORE")));

        assertEquals("INVALID_GRANT_SCOPE", error.getCode());
        verify(userRoles, never()).insert(any(UserRoleBinding.class));
    }

    @Test
    void tenantPermissionMatrixDelegatesToTenantSnapshot() {
        PermissionCatalogSnapshot snapshot = new PermissionCatalogSnapshot(List.of(), List.of(), Map.of(),
                "t1", "2026-10-29T00:00:00Z");
        when(permissionCatalog.currentTenantVersion(100L)).thenReturn("t1");
        when(permissionCatalog.tenantSnapshot(100L, false))
                .thenReturn(new PermissionCatalogApplicationService.Result(snapshot, false));

        var response = controller.tenantPermissionMatrix(false, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("t1", response.getBody().version());
        assertEquals("\"t1\"", response.getHeaders().getETag());
        verify(permissionCatalog).tenantSnapshot(100L, false);
    }

    @Test
    void rolePermissionCountsReturnsBatchCountsForVisibleRoles() {
        when(roles.listAssignableByTenant(100L)).thenReturn(List.of(role(7L, "TENANT")));
        when(roleGrants.countByRoleIds(List.of(7L))).thenReturn(Map.of(7L, 3L));

        var counts = controller.rolePermissionCounts();

        assertEquals(1, counts.size());
        assertEquals(7L, counts.get(0).roleId());
        assertEquals(3L, counts.get(0).permissionCount());
    }

    @Test
    void permissionMatrixVersionDelegatesToCatalogService() {
        when(permissionCatalog.currentVersion()).thenReturn("v1");

        var view = controller.permissionMatrixVersion();

        assertEquals("v1", view.version());
        verify(permissionCatalog).currentVersion();
    }

    @Test
    void permissionMatrixDelegatesToCatalogService() {
        PermissionCatalogSnapshot snapshot = new PermissionCatalogSnapshot(List.of(), List.of(), Map.of(),
                "v1", "2026-10-29T00:00:00Z");
        when(permissionCatalog.snapshot(false))
                .thenReturn(new PermissionCatalogApplicationService.Result(snapshot, true));

        var response = controller.permissionMatrix(false, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("v1", response.getBody().version());
        assertTrue(response.getBody().cached());
        assertEquals("\"v1\"", response.getHeaders().getETag());
        verify(permissionCatalog).snapshot(false);
    }

    @Test
    void permissionMatrixReturnsNotModifiedWhenEtagMatches() {
        when(permissionCatalog.currentVersion()).thenReturn("v1");

        var response = controller.permissionMatrix(false, "\"v1\"");

        assertEquals(304, response.getStatusCode().value());
        assertFalse(response.hasBody());
        verify(permissionCatalog, never()).snapshot(false);
    }

    @Test
    void permissionMatrixRefreshIgnoresMatchingEtag() {
        PermissionCatalogSnapshot snapshot = new PermissionCatalogSnapshot(List.of(), List.of(), Map.of(),
                "v1", "2026-10-29T00:00:00Z");
        when(permissionCatalog.currentVersion()).thenReturn("v1");
        when(permissionCatalog.snapshot(true))
                .thenReturn(new PermissionCatalogApplicationService.Result(snapshot, false));

        var response = controller.permissionMatrix(true, "\"v1\"");

        assertEquals(200, response.getStatusCode().value());
        verify(permissionCatalog).snapshot(true);
    }

    @Test
    void storeRoleCanBeAssignedToStoreScope() {
        when(roleBindings.assignByAccount(10L, 100L, null, 101L, 7L, "STORE"))
                .thenReturn(new RoleBinding(5L, 1L, 7L, "STORE", 100L, 0L, 101L, 1, "ACTIVE"));

        assertDoesNotThrow(() -> controller.assignUserRole(
                new IamController.AssignUserRoleRequest(10L, 100L, null, 101L, 7L, "STORE")));
        verify(roleBindings).assignByAccount(10L, 100L, null, 101L, 7L, "STORE");
    }

    @Test
    void revokeDelegatesToBindingService() {
        controller.revokeUserRole(5L);

        verify(roleBindings).revokeAny(5L);
        verify(snapshots, never()).evictAll();
    }

    @Test
    void revokeUnknownBindingIsNotFound() {
        org.mockito.Mockito.doThrow(new ApiException(404, "ROLE_BINDING_NOT_FOUND", "missing"))
                .when(roleBindings).revokeAny(5L);

        ApiException error = assertThrows(ApiException.class, () -> controller.revokeUserRole(5L));

        assertEquals("ROLE_BINDING_NOT_FOUND", error.getCode());
        verify(roleBindings, never()).revoke(any());
    }

    private static Role role(long id, String scope) {
        return new Role(id, 100L, "code-" + id, "name-" + id, Role.TYPE_TENANT, scope, null, null,
                Role.STATUS_ACTIVE);
    }

    private static Permission permission(long id, String code, String grantableLevels) {
        return new Permission(id, code, "tenant", "store", "manage", null, "TENANT", null,
                grantableLevels, null, Permission.STATUS_ACTIVE);
    }
}
