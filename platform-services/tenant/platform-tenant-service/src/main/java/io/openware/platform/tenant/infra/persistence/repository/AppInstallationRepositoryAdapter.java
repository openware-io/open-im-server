package io.openware.platform.tenant.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.platform.tenant.domain.app.model.AppInstallation;
import io.openware.platform.tenant.domain.app.repository.AppInstallationRepository;
import io.openware.platform.tenant.infra.persistence.mapper.AppInstallationMapper;
import io.openware.platform.tenant.infra.persistence.po.AppInstallationPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** {@link AppInstallationRepository} 持久化适配器。 */
@Repository
public class AppInstallationRepositoryAdapter implements AppInstallationRepository {

  private final AppInstallationMapper mapper;

  public AppInstallationRepositoryAdapter(AppInstallationMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<AppInstallation> findByTenantAndApp(Long tenantId, String appId) {
    return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<AppInstallationPo>()
        .eq(AppInstallationPo::getTenantId, tenantId)
        .eq(AppInstallationPo::getAppId, appId))).map(this::toDomain);
  }

  @Override
  public List<AppInstallation> findByTenant(Long tenantId) {
    return mapper.selectList(new LambdaQueryWrapper<AppInstallationPo>()
            .eq(AppInstallationPo::getTenantId, tenantId)
            .orderByAsc(AppInstallationPo::getId))
        .stream().map(this::toDomain).toList();
  }

  @Override
  public AppInstallation save(AppInstallation installation) {
    LocalDateTime now = LocalDateTime.now();
    AppInstallationPo po = new AppInstallationPo();
    po.setId(installation.id());
    po.setTenantId(installation.tenantId());
    po.setAppId(installation.appId());
    po.setClientType(installation.clientType());
    po.setStatus(installation.status());
    po.setInstalledBy(installation.installedBy());
    po.setInstalledAt(installation.installedAt());
    if (po.getId() == null) {
      po.setCreatedBy(installation.installedBy());
      po.setCreatedAt(now);
      po.setUpdatedBy(installation.installedBy());
      po.setUpdatedAt(now);
      mapper.insert(po);
    } else {
      po.setUpdatedBy(installation.installedBy());
      po.setUpdatedAt(now);
      mapper.updateById(po);
    }
    return toDomain(po);
  }

  private AppInstallation toDomain(AppInstallationPo po) {
    if (po == null) {
      return null;
    }
    return new AppInstallation(po.getId(), po.getTenantId(), po.getAppId(), po.getClientType(),
        po.getStatus(), po.getInstalledBy(), po.getInstalledAt());
  }
}
