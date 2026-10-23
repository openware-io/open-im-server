package io.openware.platform.tenant.infra.persistence.repository;

import io.openware.platform.tenant.domain.iam.model.UserRoleBinding;
import io.openware.platform.tenant.domain.iam.repository.UserRoleRepository;
import io.openware.platform.tenant.infra.persistence.mapper.IamSnapshotMapper;
import io.openware.platform.tenant.infra.persistence.mapper.UserRoleMapper;
import io.openware.platform.tenant.infra.persistence.po.UserRolePo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link UserRoleRepository} 的持久化适配器。
 *
 * <p>查询与撤销复用 {@link IamSnapshotMapper} 的专用语句：它们是授权快照装配的同一批查询，
 * 复制一份 Wrapper 会让"同一事实两处口径"再次出现。
 *
 * <p>审计字段由本层填充（{@code created_at}/{@code updated_at} 为 NOT NULL 无默认值）。
 */
@Repository
public class UserRoleRepositoryAdapter implements UserRoleRepository {

  private final UserRoleMapper mapper;
  private final IamSnapshotMapper snapshotMapper;

  public UserRoleRepositoryAdapter(UserRoleMapper mapper, IamSnapshotMapper snapshotMapper) {
    this.mapper = mapper;
    this.snapshotMapper = snapshotMapper;
  }

  @Override
  public UserRoleBinding insert(UserRoleBinding binding) {
    LocalDateTime now = LocalDateTime.now();
    UserRolePo po = new UserRolePo();
    po.setAccountId(binding.accountId());
    po.setTenantId(binding.tenantId());
    po.setOrganizationId(binding.organizationId());
    po.setStoreId(binding.storeId());
    po.setRoleId(binding.roleId());
    po.setScopeType(binding.scopeType());
    po.setEffectiveFrom(binding.effectiveFrom());
    po.setEffectiveTo(binding.effectiveTo());
    po.setStatus(binding.status());
    po.setAuthorizationVersion(binding.authorizationVersion());
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    mapper.insert(po);
    return new UserRoleBinding(po.getId(), po.getAccountId(), po.getTenantId(), po.getOrganizationId(),
        po.getStoreId(), po.getRoleId(), po.getScopeType(), po.getEffectiveFrom(), po.getEffectiveTo(),
        po.getStatus(), po.getAuthorizationVersion());
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
    return mapper.revokeByAccountAndRole(accountId, roleId) > 0;
  }

  @Override
  public boolean updateScopeByAccountAndRole(Long accountId, Long roleId, Long organizationId, Long storeId,
                                             String scopeType) {
    return mapper.updateScopeByAccountAndRole(accountId, roleId, organizationId, storeId, scopeType) > 0;
  }
}
