package io.openware.platform.tenant.api.dto;

/** 角色已授予权限数量（角色列表批量计数）。 */
public record RolePermissionCountView(Long roleId, Long permissionCount) {
}
