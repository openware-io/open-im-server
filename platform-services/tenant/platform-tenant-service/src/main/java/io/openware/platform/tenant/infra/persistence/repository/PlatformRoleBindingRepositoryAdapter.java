package io.openware.platform.tenant.infra.persistence.repository;

import io.openware.platform.tenant.domain.iam.repository.PlatformRoleBindingRepository;
import io.openware.platform.tenant.infra.persistence.mapper.RoleBindingMapper;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class PlatformRoleBindingRepositoryAdapter implements PlatformRoleBindingRepository {

  private final RoleBindingMapper mapper;

  public PlatformRoleBindingRepositoryAdapter(RoleBindingMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Long findPlatformId(Long accountId, Long roleId) {
    return mapper.selectPlatformId(accountId, roleId);
  }

  @Override
  public void insertPlatform(Long accountId, Long roleId) {
    mapper.insertPlatform(accountId, roleId);
  }

  @Override
  public boolean revokePlatform(Long bindingId) {
    return mapper.revokePlatform(bindingId) > 0;
  }

  @Override
  public List<Long> findPlatformIdsByAccount(Long accountId) {
    return mapper.selectPlatformIdsByAccount(accountId);
  }

  @Override
  public List<PlatformBinding> findPlatformBindingsByAccount(Long accountId) {
    return mapper.selectPlatformBindingsByAccount(accountId).stream()
        .map(row -> new PlatformBinding(row.id(), row.roleId()))
        .toList();
  }

  @Override
  public long countActiveByRoleId(Long roleId) {
    return mapper.countActiveByRoleId(roleId);
  }
}
