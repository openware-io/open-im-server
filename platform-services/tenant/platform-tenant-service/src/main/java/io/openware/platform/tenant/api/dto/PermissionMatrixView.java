package io.openware.platform.tenant.api.dto;

import java.util.List;

/**
 * 权限目录矩阵响应（API 契约）。
 *
 * <p>权限列表与角色矩阵来自同一快照，行序由服务端统一决定；{@code version} 是数据一致性标识，
 * {@code cached} 表示本次是否命中服务端缓存。
 */
public record PermissionMatrixView(
    List<PermissionView> permissions,
    List<RoleMatrixView> roles,
    String version,
    String generatedAt,
    boolean cached) {

  /** 矩阵列：一个全局角色及其已授予/可授予的权限编码（均按编码排序）。 */
  public record RoleMatrixView(
      Long id,
      String code,
      String name,
      String scopeLevel,
      String roleType,
      List<String> permissionCodes,
      List<String> grantablePermissionCodes) {
  }
}
