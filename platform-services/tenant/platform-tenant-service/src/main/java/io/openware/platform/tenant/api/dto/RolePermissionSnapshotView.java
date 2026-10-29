package io.openware.platform.tenant.api.dto;

import java.util.List;

/** 角色权限快照：已授予权限编码 + 乐观并发版本。 */
public record RolePermissionSnapshotView(List<String> permissionCodes, String version) {
}
