package io.openware.platform.tenant.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.tenant.domain.iam.model.Permission;
import io.openware.platform.tenant.domain.iam.repository.PermissionRepository;
import io.openware.platform.tenant.infra.persistence.mapper.PermissionMapper;
import io.openware.platform.tenant.infra.persistence.po.PermissionPo;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link PermissionRepository} 的持久化适配器（只读）。
 *
 * <p>权限点是随迁移发布的内置字典，运行时不新增，因此不提供写方法；缺失写方法本身就是约束，
 * 调用方无法从应用层插入权限点。
 */
@Repository
public class PermissionRepositoryAdapter implements PermissionRepository {

  private final PermissionMapper mapper;

  public PermissionRepositoryAdapter(PermissionMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<Permission> findByIds(List<Long> permissionIds) {
    if (permissionIds == null || permissionIds.isEmpty()) {
      return List.of();
    }
    return mapper.selectList(Wrappers.<PermissionPo>lambdaQuery().in(PermissionPo::getId, permissionIds))
        .stream().map(PermissionRepositoryAdapter::toDomain).toList();
  }

  @Override
  public Optional<Permission> findByCode(String code) {
    return mapper.selectList(Wrappers.<PermissionPo>lambdaQuery()
            .eq(PermissionPo::getCode, code)).stream().findFirst()
        .map(PermissionRepositoryAdapter::toDomain);
  }

  @Override
  public List<Permission> listActive() {
    return mapper.selectList(Wrappers.<PermissionPo>lambdaQuery()
            .eq(PermissionPo::getStatus, Permission.STATUS_ACTIVE))
        .stream().map(PermissionRepositoryAdapter::toDomain).toList();
  }

  private static Permission toDomain(PermissionPo po) {
    return new Permission(po.getId(), po.getCode(), po.getModule(), po.getResource(), po.getAction(),
        po.getDescription(), po.getScopeLevel(), po.getDomainCode(), po.getGrantableLevels(),
        po.getMenuCode(), po.getStatus());
  }
}
