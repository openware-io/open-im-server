package io.openware.platform.tenant.domain.iam.repository;

import java.util.List;

/** 平台 ACCOUNT 角色绑定。 */
public interface PlatformRoleBindingRepository {

  Long findPlatformId(Long accountId, Long roleId);

  void insertPlatform(Long accountId, Long roleId);

  boolean revokePlatform(Long bindingId);

  List<Long> findPlatformIdsByAccount(Long accountId);
}
