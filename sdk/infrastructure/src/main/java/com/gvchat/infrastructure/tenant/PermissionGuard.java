package com.gvchat.infrastructure.tenant;

import com.gvchat.common.exception.ApiException;

/** 权限守卫：按租户上下文携带的权限码做粗粒度校验，缺权限抛 403。 */
public final class PermissionGuard {
  private PermissionGuard() {}

  /** 要求当前上下文具备指定权限码，否则抛 403 PERMISSION_DENIED。 */
  public static void require(String permissionCode) {
    TenantContext ctx = TenantContextHolder.get();
    if (ctx == null || ctx.permissions() == null || !ctx.permissions().contains(permissionCode)) {
      throw new ApiException(403, "PERMISSION_DENIED", "缺少权限: " + permissionCode);
    }
  }
}