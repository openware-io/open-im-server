package io.openware.platform.tenant.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.platform.tenant.domain.iam.model.RoleBinding;
import io.openware.platform.tenant.domain.iam.repository.RoleBindingRepository;
import io.openware.platform.tenant.infra.persistence.mapper.RoleBindingMapper;
import io.openware.platform.tenant.infra.persistence.po.RoleBindingPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class RoleBindingRepositoryAdapter implements RoleBindingRepository {

  private static final String SUBJECT_MEMBERSHIP = "MEMBERSHIP";

  private final RoleBindingMapper mapper;

  public RoleBindingRepositoryAdapter(RoleBindingMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<RoleBinding> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id)).map(this::toDomain);
  }

  @Override
  public Optional<RoleBinding> findActive(Long membershipId, Long roleId, String scopeType,
                                          Long tenantId, Long organizationId, Long storeId) {
    return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<RoleBindingPo>()
        .eq(RoleBindingPo::getSubjectType, SUBJECT_MEMBERSHIP)
        .eq(RoleBindingPo::getSubjectId, membershipId)
        .eq(RoleBindingPo::getRoleId, roleId)
        .eq(RoleBindingPo::getScopeType, scopeType)
        .eq(RoleBindingPo::getTenantId, tenantId)
        .eq(RoleBindingPo::getOrganizationId, organizationId == null ? 0L : organizationId)
        .eq(RoleBindingPo::getStoreId, storeId == null ? 0L : storeId)
        .eq(RoleBindingPo::getStatus, RoleBinding.STATUS_ACTIVE))).map(this::toDomain);
  }

  @Override
  public List<RoleBinding> findByMembership(Long membershipId) {
    return mapper.selectList(new LambdaQueryWrapper<RoleBindingPo>()
            .eq(RoleBindingPo::getSubjectType, SUBJECT_MEMBERSHIP)
            .eq(RoleBindingPo::getSubjectId, membershipId)
            .orderByAsc(RoleBindingPo::getId))
        .stream().map(this::toDomain).toList();
  }

  @Override
  public RoleBinding save(RoleBinding binding) {
    LocalDateTime now = LocalDateTime.now();
    RoleBindingPo po = new RoleBindingPo();
    po.setId(binding.id());
    po.setSubjectType(SUBJECT_MEMBERSHIP);
    po.setSubjectId(binding.membershipId());
    po.setRoleId(binding.roleId());
    po.setScopeType(binding.scopeType());
    po.setTenantId(binding.tenantId());
    po.setOrganizationId(binding.organizationId());
    po.setStoreId(binding.storeId());
    po.setAuthorizationVersion(binding.authorizationVersion());
    po.setStatus(binding.status());
    if (po.getId() == null) {
      po.setCreatedBy(0L);
      po.setCreatedAt(now);
      po.setUpdatedBy(0L);
      po.setUpdatedAt(now);
      mapper.insert(po);
    } else {
      po.setUpdatedBy(0L);
      po.setUpdatedAt(now);
      mapper.updateById(po);
    }
    return toDomain(po);
  }

  private RoleBinding toDomain(RoleBindingPo po) {
    return new RoleBinding(po.getId(), po.getSubjectId(), po.getRoleId(), po.getScopeType(),
        po.getTenantId(), po.getOrganizationId(), po.getStoreId(),
        po.getAuthorizationVersion() == null ? 1 : po.getAuthorizationVersion(), po.getStatus());
  }
}
