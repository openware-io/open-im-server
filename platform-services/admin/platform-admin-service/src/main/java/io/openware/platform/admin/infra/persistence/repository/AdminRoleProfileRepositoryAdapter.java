package io.openware.platform.admin.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.platform.admin.domain.role.repository.AdminRoleProfileRepository;
import io.openware.platform.admin.infra.persistence.mapper.AdminRoleProfileMapper;
import io.openware.platform.admin.infra.persistence.po.AdminRoleProfilePo;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class AdminRoleProfileRepositoryAdapter implements AdminRoleProfileRepository {

  private final AdminRoleProfileMapper mapper;

  public AdminRoleProfileRepositoryAdapter(AdminRoleProfileMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<RoleProfile> findByRoleCodes(List<String> roleCodes) {
    if (roleCodes == null || roleCodes.isEmpty()) {
      return List.of();
    }
    return mapper.selectList(new LambdaQueryWrapper<AdminRoleProfilePo>()
            .in(AdminRoleProfilePo::getRoleCode, roleCodes)
            .eq(AdminRoleProfilePo::getStatus, "ACTIVE")
            .orderByAsc(AdminRoleProfilePo::getSortNo)
            .orderByAsc(AdminRoleProfilePo::getId))
        .stream().map(AdminRoleProfileRepositoryAdapter::toView).toList();
  }

  @Override
  public List<RoleProfile> findAll() {
    return mapper.selectList(new LambdaQueryWrapper<AdminRoleProfilePo>()
            .orderByAsc(AdminRoleProfilePo::getSortNo)
            .orderByAsc(AdminRoleProfilePo::getId))
        .stream().map(AdminRoleProfileRepositoryAdapter::toView).toList();
  }

  @Override
  public RoleProfile findById(Long id) {
    AdminRoleProfilePo po = mapper.selectById(id);
    return po == null ? null : toView(po);
  }

  @Override
  public void update(RoleProfile profile) {
    AdminRoleProfilePo po = mapper.selectById(profile.id());
    if (po == null) {
      return;
    }
    po.setWorkspaceCode(profile.workspaceCode());
    po.setLandingRoute(profile.landingRoute());
    po.setDefaultScope(profile.defaultScope());
    po.setDefaultBusinessType(profile.defaultBusinessType());
    po.setSortNo(profile.sortNo());
    mapper.updateById(po);
  }

  private static RoleProfile toView(AdminRoleProfilePo po) {
    return new RoleProfile(po.getId(), po.getRoleCode(), po.getWorkspaceCode(), po.getLandingRoute(),
        po.getDefaultScope(), po.getDefaultBusinessType(), po.getNavProfile(), po.getSortNo(), po.getStatus());
  }
}
