package io.openware.platform.tenant.domain.iam.repository;

/**
 * IAM 权限目录版本端口。
 *
 * <p>版本由 IAM 表的可变事实派生（全局角色数量/最后变更时间、全局角色授权数量/授权集合校验和/最后变更时间、
 * 可用权限数量/最后变更时间）。矩阵缓存以“版本”为键：版本不变命中缓存，版本变化自然落到新缓存键，
 * 因此多实例部署下也不需要广播失效消息；校验和覆盖“同毫秒删改但集合变化”的边角。
 */
public interface IamCatalogVersionRepository {

  /** 当前 IAM 目录版本；实现必须只读、租户无关。 */
  String currentVersion();

  /**
   * 某角色权限集合的版本（角色最后变更时间 + 授权数量 + 授权集合校验和 + 授权最后变更时间）。
   *
   * <p>用于角色权限编辑的乐观并发控制：读取快照时带版本，保存时校验，不一致返回 409，避免覆盖
   * 其他管理员的修改。
   */
  String rolePermissionVersion(Long roleId);
}
