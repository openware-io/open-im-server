package io.openware.platform.tenant.domain.iam.repository;

import io.openware.platform.tenant.domain.iam.model.RoleBinding;
import java.util.List;
import java.util.Optional;

/** 成员-角色绑定仓储端口。 */
public interface RoleBindingRepository {

  Optional<RoleBinding> findById(Long id);

  Optional<RoleBinding> findActive(Long membershipId, Long roleId, String scopeType,
                                   Long tenantId, Long organizationId, Long storeId);

  List<RoleBinding> findByMembership(Long membershipId);

  List<Long> findPlatformIdsByAccount(Long accountId);

  java.util.Optional<Long> findAccountIdByAnyId(Long bindingId);

  boolean revokeAnyById(Long bindingId);

  RoleBinding save(RoleBinding binding);
}
