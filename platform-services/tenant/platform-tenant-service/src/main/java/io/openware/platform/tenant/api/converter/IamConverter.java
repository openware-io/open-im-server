package io.openware.platform.tenant.api.converter;

import io.openware.platform.tenant.api.dto.PermissionView;
import io.openware.platform.tenant.api.dto.RoleView;
import io.openware.platform.tenant.api.dto.UserRoleView;
import io.openware.platform.tenant.domain.iam.model.Permission;
import io.openware.platform.tenant.domain.iam.model.Role;
import io.openware.platform.tenant.domain.iam.model.RoleBinding;
import io.openware.platform.tenant.domain.iam.model.UserRoleBinding;

/**
 * IAM 领域模型到 API 契约的转换（standards/10“转换层职责切分”）。
 *
 * <p>转换收在这里而不是放进 DTO 本身：DTO 只描述对外形状，让它 import 领域模型会把 API 契约反向绑到
 * 领域对象上。
 */
public final class IamConverter {

  private IamConverter() {
  }

  /** 角色转对外视图；入参为空时返回空。 */
  public static RoleView toView(Role role) {
    if (role == null) {
      return null;
    }
    return new RoleView(role.id(), role.tenantId(), role.code(), role.name(), role.roleType(),
        role.scopeLevel(), role.domainCode(), role.copyFromRoleId(), role.status());
  }

  /** 权限点转对外视图；入参为空时返回空。 */
  public static PermissionView toView(Permission permission) {
    if (permission == null) {
      return null;
    }
    return new PermissionView(permission.id(), permission.code(), permission.module(), permission.resource(),
        permission.action(), permission.description(), permission.scopeLevel(), permission.domainCode(),
        permission.grantableLevels(), permission.menuCode(), permission.status());
  }

  /** 成员绑定转对外视图；账号由 membership 反查。 */
  public static UserRoleView toView(RoleBinding binding, Long accountId) {
    if (binding == null) {
      return null;
    }
    return new UserRoleView(binding.id(), accountId, binding.tenantId(), binding.organizationId(),
        binding.storeId(), binding.roleId(), binding.scopeType(), null, null,
        binding.status(), binding.authorizationVersion());
  }

  /** 绑定转对外视图；入参为空时返回空。 */
  public static UserRoleView toView(UserRoleBinding binding) {
    if (binding == null) {
      return null;
    }
    return new UserRoleView(binding.id(), binding.accountId(), binding.tenantId(), binding.organizationId(),
        binding.storeId(), binding.roleId(), binding.scopeType(), binding.effectiveFrom(),
        binding.effectiveTo(), binding.status(), binding.authorizationVersion());
  }
}
