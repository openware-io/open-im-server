package io.openware.platform.tenant.infra.persistence.repository;

import io.openware.common.exception.ApiException;
import io.openware.platform.tenant.domain.iam.model.UserRoleBinding;
import io.openware.platform.tenant.domain.iam.repository.UserRoleRepository;
import io.openware.platform.tenant.infra.persistence.mapper.IamSnapshotMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 旧 iam_user_role 仓库冻结适配器。
 *
 * <p>读写已全部切到 iam_role_binding；旧表已由 V16 归档为 iam_user_role_archive。
 * 保留该 Bean 仅为兼容旧服务构造签名，任何旧写入口直接 410。
 */
@Repository
public class UserRoleRepositoryAdapter implements UserRoleRepository {

  private final IamSnapshotMapper snapshotMapper;

  public UserRoleRepositoryAdapter(IamSnapshotMapper snapshotMapper) {
    this.snapshotMapper = snapshotMapper;
  }

  @Override
  public UserRoleBinding insert(UserRoleBinding binding) {
    throw legacyWrite();
  }

  @Override
  public Optional<Long> findAccountIdById(Long userRoleId) {
    return Optional.ofNullable(snapshotMapper.selectAccountIdByUserRole(userRoleId));
  }

  @Override
  public boolean revoke(Long userRoleId) {
    return snapshotMapper.revokeUserRole(userRoleId) > 0;
  }

  @Override
  public List<Long> findIdsByAccount(Long accountId) {
    return snapshotMapper.selectUserRoleIds(accountId);
  }

  @Override
  public boolean revokeByAccountAndRole(Long accountId, Long roleId) {
    throw legacyWrite();
  }

  @Override
  public boolean updateScopeByAccountAndRole(Long accountId, Long roleId, Long organizationId, Long storeId,
                                             String scopeType) {
    throw legacyWrite();
  }

  private static ApiException legacyWrite() {
    return new ApiException(410, "LEGACY_ROLE_WRITE_DISABLED",
        "旧 iam_user_role 写入口已停用，请使用 iam_role_binding");
  }
}
