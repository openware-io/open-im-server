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

  /**
   * 按标识读取角色并加行锁（写路径专用，必须在事务内调用）。
   *
   * <p>用于角色权限编辑的并发串行化：先锁角色行，再校验版本/替换授权，避免 check-then-act 竞态。
   */
  Optional<Role> findByIdForUpdate(Long roleId);

  /** 新增角色，返回落库后的聚合（含回填主键）。 */
  Role insert(Role role);

  /** 列出某租户的**可用**角色。 */
  List<Role> listActiveByTenant(Long tenantId);

  /** 列出可分配给该租户的角色：全局预置角色 + 该租户自定义角色，不含平台角色。 */
  List<Role> listAssignableByTenant(Long tenantId);

  /** 平台角色目录，仅平台上下文使用。 */
  List<Role> listPlatformRoles();

  /**
   * 全局角色定义（{@code tenant_id IS NULL} 的预置角色与平台自定义角色）。
   *
   * <p>用于平台级权限目录矩阵：这些角色是平台统一维护的授权模板，不属于任何租户。
   */
  List<Role> listGlobalRoles();

  /**
   * 遮蔽（脱敏）角色视图：角色 + 其权限中标记了可见 PII 的项。
   *
   * <p>这是跨表派生的只读投影，返回具名类型 {@link MaskingRole}，不返回行对象或 Map。
   */
  List<MaskingRole> listMaskingRoles();
}
