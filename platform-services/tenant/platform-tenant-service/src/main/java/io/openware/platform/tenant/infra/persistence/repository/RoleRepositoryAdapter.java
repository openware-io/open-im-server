package io.openware.platform.tenant.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.tenant.domain.iam.model.MaskingRole;
import io.openware.platform.tenant.domain.iam.model.Role;
import io.openware.platform.tenant.domain.iam.repository.RoleRepository;
import io.openware.platform.tenant.infra.persistence.mapper.IamSnapshotMapper;
import io.openware.platform.tenant.infra.persistence.mapper.RoleMapper;
import io.openware.platform.tenant.infra.persistence.po.RolePo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link RoleRepository} 的持久化适配器。
 *
 * <p>遮蔽视图复用 {@link IamSnapshotMapper#selectMaskingRoles()}：该查询是跨表派生的只读投影，
 * 用 lambda Wrapper 表达不了，因此留在 mapper 里、由本层翻译成具名投影类型。
 *
 * <p>审计字段由本层填充（{@code created_at}/{@code updated_at} 为 NOT NULL 无默认值）。
 */
@Repository
public class RoleRepositoryAdapter implements RoleRepository {

  private final RoleMapper mapper;
  private final IamSnapshotMapper snapshotMapper;

  public RoleRepositoryAdapter(RoleMapper mapper, IamSnapshotMapper snapshotMapper) {
    this.mapper = mapper;
    this.snapshotMapper = snapshotMapper;
  }

  @Override
  public Optional<Role> findById(Long roleId) {
    return Optional.ofNullable(mapper.selectByIdIgnoringTenant(roleId)).map(RoleRepositoryAdapter::toDomain);
  }

  @Override
  public Role insert(Role role) {
    LocalDateTime now = LocalDateTime.now();
    RolePo po = toPo(role);
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    mapper.insert(po);
    return toDomain(po);
  }

  @Override
  public List<Role> listActiveByTenant(Long tenantId) {
    return mapper.selectList(Wrappers.<RolePo>lambdaQuery()
            .eq(RolePo::getTenantId, tenantId)
            .eq(RolePo::getStatus, Role.STATUS_ACTIVE))
        .stream().map(RoleRepositoryAdapter::toDomain).toList();
  }

  @Override
  public List<Role> listAssignableByTenant(Long tenantId) {
    return mapper.selectAssignableByTenant(tenantId).stream()
        .map(RoleRepositoryAdapter::toDomain).toList();
  }

  @Override
  public List<Role> listPlatformRoles() {
    return mapper.selectPlatformRoles().stream()
        .map(RoleRepositoryAdapter::toDomain).toList();
  }

  @Override
  public List<MaskingRole> listMaskingRoles() {
    return snapshotMapper.selectMaskingRoles().stream()
        .map(row -> new MaskingRole(row.getRoleId(), row.getCode(), row.getName(),
            row.getPiiView() != null && row.getPiiView()))
        .toList();
  }

  private static RolePo toPo(Role role) {
    RolePo po = new RolePo();
    po.setId(role.id());
    po.setTenantId(role.tenantId());
    po.setCode(role.code());
    po.setName(role.name());
    po.setRoleType(role.roleType());
    po.setScopeLevel(role.scopeLevel());
    po.setDomainCode(role.domainCode());
    po.setCopyFromRoleId(role.copyFromRoleId());
    po.setStatus(role.status());
    return po;
  }

  private static Role toDomain(RolePo po) {
    return new Role(po.getId(), po.getTenantId(), po.getCode(), po.getName(), po.getRoleType(),
        po.getScopeLevel(), po.getDomainCode(), po.getCopyFromRoleId(), po.getStatus());
  }
}
