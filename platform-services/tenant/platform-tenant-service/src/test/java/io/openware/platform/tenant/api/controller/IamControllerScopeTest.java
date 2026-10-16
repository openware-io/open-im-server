package io.openware.platform.tenant.api.controller;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.platform.tenant.application.IamAuthorizationApplicationService;
import io.openware.platform.tenant.application.PermissionSnapshotProvider;
import io.openware.platform.tenant.domain.iam.model.Permission;
import io.openware.platform.tenant.domain.iam.model.Role;
import io.openware.platform.tenant.domain.iam.model.UserRoleBinding;
import io.openware.platform.tenant.domain.iam.repository.PermissionRepository;
import io.openware.platform.tenant.domain.iam.repository.RoleGrantRepository;
import io.openware.platform.tenant.domain.iam.repository.RoleRepository;
import io.openware.platform.tenant.domain.iam.repository.UserRoleRepository;
import java.util.List;
import java.util.Optional;
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
    private final AuditClient audit = mock(AuditClient.class);
    private final IamAuthorizationApplicationService authorization =
            new IamAuthorizationApplicationService(roles, permissions, roleGrants, userRoles, snapshots);
    private final IamController controller =
            new IamController(roles, permissions, userRoles, authorization, audit);

    @Test
    void storeRoleCannotReceiveTenantOnlyPermission() {
        when(roles.findById(7L)).thenReturn(Optional.of(role(7L, "STORE")));
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
        when(roles.findById(7L)).thenReturn(Optional.of(role(7L, "TENANT")));
        when(permissions.findByIds(List.of(9L, 999L)))
                .thenReturn(List.of(permission(9L, "a.b", "TENANT")));

        ApiException error = assertThrows(ApiException.class, () -> controller.assignPermissions(7L,
                new IamController.AssignPermissionsRequest(List.of(9L, 999L))));

        assertEquals("INVALID_GRANT_SCOPE", error.getCode());
        verify(roleGrants, never()).replaceRolePermissions(any(), any());
    }

    @Test
    void inactiveRoleIsNotFoundAndNothingIsWritten() {
        when(roles.findById(7L)).thenReturn(Optional.empty());

        ApiException error = assertThrows(ApiException.class,
                () -> controller.assignPermissions(7L, new IamController.AssignPermissionsRequest(List.of())));

        assertEquals("ROLE_NOT_FOUND", error.getCode());
        verify(roleGrants, never()).replaceRolePermissions(any(), any());
    }

    @Test
    void tenantRoleCannotBeAssignedToStoreScope() {
        when(roles.findById(7L)).thenReturn(Optional.of(role(7L, "TENANT")));

        ApiException error = assertThrows(ApiException.class, () -> controller.assignUserRole(
                new IamController.AssignUserRoleRequest(10L, 100L, null, 101L, 7L, "STORE")));

        assertEquals("INVALID_GRANT_SCOPE", error.getCode());
        verify(userRoles, never()).insert(any(UserRoleBinding.class));
    }

    @Test
    void storeRoleCanBeAssignedToStoreScope() {
        when(roles.findById(7L)).thenReturn(Optional.of(role(7L, "STORE")));
        when(userRoles.insert(any(UserRoleBinding.class))).thenAnswer(i -> i.getArgument(0));

        assertDoesNotThrow(() -> controller.assignUserRole(
                new IamController.AssignUserRoleRequest(10L, 100L, null, 101L, 7L, "STORE")));
        verify(userRoles).insert(any(UserRoleBinding.class));
    }

    /** 撤销只逐出被撤销账号的缓存，不得全量逐出（那会打掉所有账号的快照）。 */
    @Test
    void revokeEvictsOnlyTheAffectedAccount() {
        when(userRoles.findAccountIdById(5L)).thenReturn(Optional.of(10L));
        when(userRoles.revoke(5L)).thenReturn(true);

        controller.revokeUserRole(5L);

        verify(snapshots).evict(10L);
        verify(snapshots, never()).evictAll();
    }

    /** 绑定不存在必须 404，而不是静默成功。 */
    @Test
    void revokeUnknownBindingIsNotFound() {
        when(userRoles.findAccountIdById(5L)).thenReturn(Optional.empty());

        ApiException error = assertThrows(ApiException.class, () -> controller.revokeUserRole(5L));

        assertEquals("USER_ROLE_NOT_FOUND", error.getCode());
        verify(userRoles, never()).revoke(any());
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
