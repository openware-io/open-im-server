package io.openware.platform.tenant.domain.iam.repository;

import java.util.List;

/** 平台 ACCOUNT 角色绑定。 */
public interface PlatformRoleBindingRepository {

  Long findPlatformId(Long accountId, Long roleId);

  void insertPlatform(Long accountId, Long roleId);

  boolean revokePlatform(Long bindingId);

  List<Long> findPlatformIdsByAccount(Long accountId);

  /** 账号的平台角色绑定（绑定 id + 角色 id），用于运营人员管理页展示/撤销。 */
  List<PlatformBinding> findPlatformBindingsByAccount(Long accountId);

  /** 某平台角色当前有效绑定数量；停用/删除前必须为 0。 */
  long countActiveByRoleId(Long roleId);

  record PlatformBinding(Long id, Long roleId) {
  }
}
