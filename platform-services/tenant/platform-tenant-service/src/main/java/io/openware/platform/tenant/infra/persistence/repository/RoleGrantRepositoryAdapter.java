package io.openware.platform.tenant.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.tenant.domain.iam.repository.RoleGrantRepository;
import io.openware.platform.tenant.infra.persistence.mapper.RolePermissionMapper;
import io.openware.platform.tenant.infra.persistence.po.RolePermissionPo;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * {@link RoleGrantRepository} 的持久化适配器。
 *
 * <p>{@code grant} 先查后插（而非依赖唯一键异常）：授予是可重复调用的幂等操作，用异常做流程控制会把
 * "已授予"变成一次失败。并发下的极小窗口由 {@code iam_role_permission} 的唯一键兜底。
 */
@Repository
public class RoleGrantRepositoryAdapter implements RoleGrantRepository {

  private final RolePermissionMapper mapper;

  public RoleGrantRepositoryAdapter(RolePermissionMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void replaceRolePermissions(Long roleId, List<Long> permissionIds) {
    mapper.delete(Wrappers.<RolePermissionPo>lambdaQuery().eq(RolePermissionPo::getRoleId, roleId));
    if (permissionIds == null) {
      return;
    }
    for (Long permissionId : permissionIds) {
      grant(roleId, permissionId);
    }
  }

  @Override
  public List<Long> findPermissionIdsByRole(Long roleId) {
    return mapper.selectList(Wrappers.<RolePermissionPo>lambdaQuery()
            .eq(RolePermissionPo::getRoleId, roleId))
        .stream().map(RolePermissionPo::getPermissionId).toList();
  }

  @Override
  public boolean hasGrant(Long roleId, Long permissionId) {
    return mapper.selectCount(Wrappers.<RolePermissionPo>lambdaQuery()
        .eq(RolePermissionPo::getRoleId, roleId)
        .eq(RolePermissionPo::getPermissionId, permissionId)) > 0;
  }

  @Override
  public void revoke(Long roleId, Long permissionId) {
    mapper.delete(Wrappers.<RolePermissionPo>lambdaQuery()
        .eq(RolePermissionPo::getRoleId, roleId)
        .eq(RolePermissionPo::getPermissionId, permissionId));
  }

  @Override
  public void grant(Long roleId, Long permissionId) {
    if (hasGrant(roleId, permissionId)) {
      return;
    }
    RolePermissionPo po = new RolePermissionPo();
    po.setRoleId(roleId);
    po.setPermissionId(permissionId);
    java.time.LocalDateTime now = java.time.LocalDateTime.now();
    po.setCreatedBy(0L);
    po.setCreatedAt(now);
    po.setUpdatedBy(0L);
    po.setUpdatedAt(now);
    mapper.insert(po);
  }
}
