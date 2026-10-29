package io.openware.platform.tenant.domain.iam.repository;

import java.util.List;
import java.util.Map;

/**
 * 角色-权限授予端口。
 *
 * <p>授予是"角色"这个授权边界的写入面，因此独立于 {@link RoleRepository}：
 * 前者读角色事实，这里改授权关系。
 */
public interface RoleGrantRepository {

  /**
   * 用给定权限集合**整体替换**某角色的授予关系（先清空再写入）。
   *
   * <p>替换语义而不是增量：调用方提交的就是该角色的完整权限集，增量语义会让"撤销某个权限"
   * 无法表达（不提交即保留）。
   */
  void replaceRolePermissions(Long roleId, List<Long> permissionIds);

  /** 列出某角色已授予的权限标识。 */
  List<Long> findPermissionIdsByRole(Long roleId);

  /**
   * 批量读取角色已授予的权限标识。
   *
   * <p>返回的 Map 只包含 {@code roleIds} 中给了授权的角色；未授权的角色不出现。用于权限目录矩阵，
   * 避免按角色逐个查询产生 N+1。
   */
  Map<Long, List<Long>> findPermissionIdsByRoles(List<Long> roleIds);

  /** 判断某角色是否已授予某权限（toggle 用例的判据）。 */
  boolean hasGrant(Long roleId, Long permissionId);

  /** 撤销单个授予（幂等：不存在时不报错）。 */
  void revoke(Long roleId, Long permissionId);

  /** 新增单个授予（幂等：已存在时不重复插入）。 */
  void grant(Long roleId, Long permissionId);
}
