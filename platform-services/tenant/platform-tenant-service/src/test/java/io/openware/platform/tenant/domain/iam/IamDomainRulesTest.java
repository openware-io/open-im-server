package io.openware.platform.tenant.domain.iam;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.platform.tenant.domain.iam.model.Permission;
import io.openware.platform.tenant.domain.iam.model.UserRoleBinding;
import org.junit.jupiter.api.Test;

/**
 * IAM 授权边界规则（纯领域，无 Spring、无持久化）。
 *
 * <p>这两条规则此前内联在控制器的 if 表达式里，只能通过构造完整控制器 + mock 四个 mapper 才能验证，
 * 且只测了当时写下的那几个组合。移到领域模型后可以直接穷举——下面的用例数比迁移前多，正是因为
 * 规则现在可独立调用。
 */
class IamDomainRulesTest {

  // ---------- Permission.grantableTo：作用域白名单 ----------

  @Test
  void grantableWhenRoleScopeInWhitelist() {
    Permission permission = permission("TENANT,STORE");

    assertTrue(permission.grantableTo("TENANT"));
    assertTrue(permission.grantableTo("STORE"));
  }

  @Test
  void notGrantableWhenRoleScopeOutsideWhitelist() {
    Permission permission = permission("TENANT");

    assertFalse(permission.grantableTo("STORE"), "门店级角色不得拿到仅租户级可授予的权限");
    assertFalse(permission.grantableTo("PLATFORM"));
  }

  @Test
  void notGrantableOnMissingInputs() {
    assertFalse(permission(null).grantableTo("TENANT"), "白名单缺失必须 fail-closed");
    assertFalse(permission("").grantableTo("TENANT"), "空白名单必须 fail-closed");
    assertFalse(permission("   ").grantableTo("TENANT"), "纯空白名单必须 fail-closed");
    assertFalse(permission("TENANT").grantableTo(null), "作用域缺失必须 fail-closed");
  }

  /** 白名单逐项精确匹配：不得让 TENANT 通过包含关系配到 TENANT_ADMIN。 */
  @Test
  void whitelistMatchingIsExactNotPrefix() {
    assertFalse(permission("TENANT_ADMIN").grantableTo("TENANT"));
    assertFalse(permission("TENANT").grantableTo("TENANT_ADMIN"));
  }

  /** 白名单项两侧空白必须被容忍（字典里写成 "TENANT, STORE" 是常见笔误）。 */
  @Test
  void whitelistToleratesSurroundingWhitespace() {
    assertTrue(permission("TENANT, STORE").grantableTo("STORE"));
    assertTrue(permission(" TENANT ,STORE ").grantableTo("TENANT"));
  }

  // ---------- UserRoleBinding.scopeShapeValid：作用域与归属字段自洽 ----------

  @Test
  void platformScopeMustCarryNoOwnership() {
    assertTrue(UserRoleBinding.scopeShapeValid("PLATFORM", null, null, null));
    assertFalse(UserRoleBinding.scopeShapeValid("PLATFORM", 100L, null, null),
        "平台级授权挂了租户，会让该账号在某个租户下获得平台能力");
    assertFalse(UserRoleBinding.scopeShapeValid("PLATFORM", null, 5L, null));
    assertFalse(UserRoleBinding.scopeShapeValid("PLATFORM", null, null, 9L));
  }

  @Test
  void tenantScopeRequiresTenantAndForbidsStore() {
    assertTrue(UserRoleBinding.scopeShapeValid("TENANT", 100L, null, null));
    assertTrue(UserRoleBinding.scopeShapeValid("TENANT", 100L, 5L, null), "组织归属不影响租户级判定");
    assertFalse(UserRoleBinding.scopeShapeValid("TENANT", null, null, null), "租户级必须带租户");
    assertFalse(UserRoleBinding.scopeShapeValid("TENANT", 100L, null, 9L),
        "带门店的租户级实际是门店级授权");
  }

  @Test
  void storeScopeRequiresBothTenantAndStore() {
    assertTrue(UserRoleBinding.scopeShapeValid("STORE", 100L, null, 9L));
    assertTrue(UserRoleBinding.scopeShapeValid("STORE", 100L, 5L, 9L));
    assertFalse(UserRoleBinding.scopeShapeValid("STORE", null, null, 9L));
    assertFalse(UserRoleBinding.scopeShapeValid("STORE", 100L, null, null));
  }

  /** 未知作用域必须拒绝（fail-closed）：新增作用域时忘记登记不能静默放行。 */
  @Test
  void unknownScopeIsRejected() {
    assertFalse(UserRoleBinding.scopeShapeValid(null, 100L, null, null));
    assertFalse(UserRoleBinding.scopeShapeValid("REGION", 100L, null, null));
    assertFalse(UserRoleBinding.scopeShapeValid("platform", null, null, null), "大小写敏感");
  }

  private static Permission permission(String grantableLevels) {
    return new Permission(9L, "tenant.store.manage", "tenant", "store", "manage", null, "TENANT", null,
        grantableLevels, null, Permission.STATUS_ACTIVE);
  }
}
