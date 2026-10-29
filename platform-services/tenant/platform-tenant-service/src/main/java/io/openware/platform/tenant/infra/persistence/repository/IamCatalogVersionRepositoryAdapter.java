package io.openware.platform.tenant.infra.persistence.repository;

import io.openware.platform.tenant.domain.iam.repository.IamCatalogVersionRepository;
import io.openware.platform.tenant.infra.persistence.mapper.IamCatalogVersionMapper;
import org.springframework.stereotype.Repository;

/** {@link IamCatalogVersionRepository} 的持久化适配器。 */
@Repository
public class IamCatalogVersionRepositoryAdapter implements IamCatalogVersionRepository {

  private final IamCatalogVersionMapper mapper;

  public IamCatalogVersionRepositoryAdapter(IamCatalogVersionMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public String currentVersion() {
    String version = mapper.selectVersion();
    return version == null || version.isBlank() ? "unknown" : version;
  }

  @Override
  public String rolePermissionVersion(Long roleId) {
    String version = mapper.selectRolePermissionVersion(roleId);
    return version == null || version.isBlank() ? "unknown" : version;
  }

  @Override
  public String tenantVersion(Long tenantId) {
    String version = mapper.selectTenantVersion(tenantId);
    return version == null || version.isBlank() ? "unknown" : version;
  }
}
