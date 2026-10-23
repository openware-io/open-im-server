package io.openware.platform.tenant.domain.iam.repository;

import io.openware.platform.tenant.domain.iam.model.MaskingRole;
import io.openware.platform.tenant.domain.iam.model.Role;
import java.util.List;
import java.util.Optional;

/**
 * 角色仓储端口。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 */
public interface RoleRepository {

  /** 按标识读取角色。 */
  Optional<Role> findById(Long roleId);

  /** 新增角色，返回落库后的聚合（含回填主键）。 */
  Role insert(Role role);

  /** 列出某租户的**可用**角色。 */
  List<Role> listActiveByTenant(Long tenantId);

  /** 列出可分配给该租户的角色：全局预置角色 + 该租户自定义角色，不含平台角色。 */
  List<Role> listAssignableByTenant(Long tenantId);

  /**
   * 遮蔽（脱敏）角色视图：角色 + 其权限中标记了可见 PII 的项。
   *
   * <p>这是跨表派生的只读投影，返回具名类型 {@link MaskingRole}，不返回行对象或 Map。
   */
  List<MaskingRole> listMaskingRoles();
}
