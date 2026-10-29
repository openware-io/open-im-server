package io.openware.platform.tenant.api.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.platform.tenant.api.dto.PermissionMatrixView;
import io.openware.platform.tenant.domain.iam.model.Permission;
import io.openware.platform.tenant.domain.iam.model.PermissionCatalogSnapshot;
import io.openware.platform.tenant.domain.iam.model.Role;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 权限目录矩阵转换：权限编码对齐、授予编码排序、未知权限过滤、可授予集合按角色作用域计算。 */
class PermissionCatalogConverterTest {

  @Test
  void convertsGrantsToSortedKnownPermissionCodesAndComputesGrantableCodes() {
    Permission tenantPermission = permission(1L, "tenant.store.manage", "TENANT");
    Permission orderPermission = permission(2L, "order.view", "TENANT,STORE");
    Role owner = role(10L, "tenant.owner", "TENANT");
    Role cashier = role(11L, "store.cashier", "STORE");
    PermissionCatalogSnapshot snapshot = new PermissionCatalogSnapshot(
        List.of(tenantPermission, orderPermission),
        List.of(owner, cashier),
        Map.of(10L, List.of(2L, 1L), 11L, List.of(999L)),
        "v1", "2026-10-29T00:00:00Z");

    PermissionMatrixView view = PermissionCatalogConverter.toView(snapshot, true);

    assertEquals(2, view.permissions().size());
    assertTrue(view.cached());
    assertEquals("v1", view.version());
    assertEquals(List.of("order.view", "tenant.store.manage"), view.roles().get(0).permissionCodes());
    assertEquals(List.of(), view.roles().get(1).permissionCodes());
    assertEquals(List.of("order.view", "tenant.store.manage"),
        view.roles().get(0).grantablePermissionCodes());
    assertEquals(List.of("order.view"), view.roles().get(1).grantablePermissionCodes());
  }

  private static Permission permission(long id, String code, String grantableLevels) {
    return new Permission(id, code, "tenant", "store", "manage", code, "TENANT", "core",
        grantableLevels, "store", "ACTIVE");
  }

  private static Role role(long id, String code, String scope) {
    return new Role(id, null, code, code, "PRESET", scope, null, null, "ACTIVE");
  }
}
