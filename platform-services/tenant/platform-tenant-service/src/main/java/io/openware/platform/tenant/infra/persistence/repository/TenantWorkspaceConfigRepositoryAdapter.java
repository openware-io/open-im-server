package io.openware.platform.tenant.infra.persistence.repository;

import io.openware.platform.tenant.domain.tenant.repository.TenantWorkspaceConfigRepository;
import io.openware.platform.tenant.infra.persistence.mapper.TenantWorkspaceConfigMapper;
import io.openware.platform.tenant.infra.persistence.po.TenantWorkspaceConfigPo;
import java.time.LocalDateTime;
import org.springframework.stereotype.Repository;

@Repository
public class TenantWorkspaceConfigRepositoryAdapter implements TenantWorkspaceConfigRepository {

  private final TenantWorkspaceConfigMapper mapper;

  public TenantWorkspaceConfigRepositoryAdapter(TenantWorkspaceConfigMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public String findStoreWorkspaceMode(long tenantId) {
    TenantWorkspaceConfigPo po = mapper.selectByTenantIgnoringTenant(tenantId);
    return po == null || po.getStoreWorkspaceMode() == null ? null : po.getStoreWorkspaceMode();
  }

  @Override
  public void saveStoreWorkspaceMode(long tenantId, String mode, long actorId) {
    TenantWorkspaceConfigPo existing = mapper.selectByTenantIgnoringTenant(tenantId);
    LocalDateTime now = LocalDateTime.now();
    if (existing == null) {
      TenantWorkspaceConfigPo po = new TenantWorkspaceConfigPo();
      po.setTenantId(tenantId);
      po.setStoreWorkspaceMode(mode);
      po.setCreatedBy(actorId);
      po.setCreatedAt(now);
      po.setUpdatedBy(actorId);
      po.setUpdatedAt(now);
      mapper.insert(po);
      return;
    }
    existing.setStoreWorkspaceMode(mode);
    existing.setUpdatedBy(actorId);
    existing.setUpdatedAt(now);
    mapper.updateById(existing);
  }
}
