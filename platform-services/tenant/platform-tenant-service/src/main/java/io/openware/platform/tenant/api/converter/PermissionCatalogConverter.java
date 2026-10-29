package io.openware.platform.tenant.api.converter;

import io.openware.platform.tenant.api.dto.PermissionMatrixView;
import io.openware.platform.tenant.api.dto.PermissionMatrixView.RoleMatrixView;
import io.openware.platform.tenant.domain.iam.model.Permission;
import io.openware.platform.tenant.domain.iam.model.PermissionCatalogSnapshot;
import io.openware.platform.tenant.domain.iam.model.Role;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 权限目录快照到 API 契约的转换。
 *
 * <p>矩阵用权限编码而不是权限 ID 与前端对齐：编码是跨环境稳定的业务键，权限 ID 只在单库内有意义。
 */
public final class PermissionCatalogConverter {

  private PermissionCatalogConverter() {
  }

  public static PermissionMatrixView toView(PermissionCatalogSnapshot snapshot, boolean cached) {
    Map<Long, String> codeById = new LinkedHashMap<>();
    for (Permission permission : snapshot.permissions()) {
      codeById.put(permission.id(), permission.code());
    }
    List<RoleMatrixView> roles = snapshot.roles().stream()
        .map(role -> toRoleView(role, snapshot.rolePermissionIds(), snapshot.permissions(), codeById))
        .toList();
    return new PermissionMatrixView(
        snapshot.permissions().stream().map(IamConverter::toView).toList(),
        roles,
        snapshot.version(),
        snapshot.generatedAt(),
        cached);
  }

  private static RoleMatrixView toRoleView(Role role, Map<Long, List<Long>> grants,
                                           List<Permission> permissions, Map<Long, String> codeById) {
    List<String> permissionCodes = grants.getOrDefault(role.id(), List.of()).stream()
        .map(codeById::get)
        .filter(Objects::nonNull)
        .sorted()
        .toList();
    // 可授予判定复用领域规则（Permission.grantableTo），前端不重复实现授权语义。
    List<String> grantablePermissionCodes = permissions.stream()
        .filter(permission -> permission.grantableTo(role.scopeLevel()))
        .map(Permission::code)
        .sorted()
        .toList();
    return new RoleMatrixView(role.id(), role.code(), role.name(), role.scopeLevel(),
        role.roleType(), permissionCodes, grantablePermissionCodes);
  }
}
