package io.openware.platform.tenant.api.controller;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.platform.tenant.application.PermissionSnapshotProvider;
import io.openware.platform.tenant.infra.persistence.mapper.PermissionMapper;
import io.openware.platform.tenant.infra.persistence.mapper.RoleMapper;
import io.openware.platform.tenant.infra.persistence.mapper.RolePermissionMapper;
import io.openware.platform.tenant.infra.persistence.mapper.UserRoleMapper;
import io.openware.platform.tenant.infra.persistence.po.PermissionPo;
import io.openware.platform.tenant.infra.persistence.po.RolePo;
import io.openware.platform.tenant.infra.persistence.po.UserRolePo;
import java.util.List;
import org.junit.jupiter.api.Test;

class IamControllerScopeTest {
    private final RoleMapper roles = mock(RoleMapper.class);
    private final PermissionMapper permissions = mock(PermissionMapper.class);
    private final RolePermissionMapper rolePermissions = mock(RolePermissionMapper.class);
    private final UserRoleMapper userRoles = mock(UserRoleMapper.class);
    private final PermissionSnapshotProvider snapshots = mock(PermissionSnapshotProvider.class);
    private final AuditClient audit = mock(AuditClient.class);
    private final IamController controller =
            new IamController(roles, permissions, rolePermissions, userRoles, snapshots, audit);

    @Test
    void storeRoleCannotReceiveTenantOnlyPermission() {
        RolePo role = role(7L, "STORE");
        PermissionPo permission = permission(9L, "tenant.store.manage", "TENANT");
        when(roles.selectById(7L)).thenReturn(role);
        when(permissions.selectList(any())).thenReturn(List.of(permission));

        ApiException error = assertThrows(ApiException.class,
                () -> controller.assignPermissions(7L, new IamController.AssignPermissionsRequest(List.of(9L))));

        org.junit.jupiter.api.Assertions.assertEquals("INVALID_GRANT_SCOPE", error.getCode());
        verify(rolePermissions, never()).delete(any());
    }

    @Test
    void tenantRoleCannotBeAssignedToStoreScope() {
        when(roles.selectById(7L)).thenReturn(role(7L, "TENANT"));

        ApiException error = assertThrows(ApiException.class, () -> controller.assignUserRole(
                new IamController.AssignUserRoleRequest(10L, 100L, null, 101L, 7L, "STORE")));

        org.junit.jupiter.api.Assertions.assertEquals("INVALID_GRANT_SCOPE", error.getCode());
        verify(userRoles, never()).insert(any(UserRolePo.class));
    }

    @Test
    void storeRoleCanBeAssignedToStoreScope() {
        when(roles.selectById(7L)).thenReturn(role(7L, "STORE"));

        assertDoesNotThrow(() -> controller.assignUserRole(
                new IamController.AssignUserRoleRequest(10L, 100L, null, 101L, 7L, "STORE")));
        verify(userRoles).insert(any(UserRolePo.class));
    }

    private static RolePo role(long id, String scope) {
        RolePo role = new RolePo();
        role.setId(id);
        role.setScopeLevel(scope);
        role.setStatus("ACTIVE");
        return role;
    }

    private static PermissionPo permission(long id, String code, String grantableLevels) {
        PermissionPo permission = new PermissionPo();
        permission.setId(id);
        permission.setCode(code);
        permission.setGrantableLevels(grantableLevels);
        return permission;
    }
}
