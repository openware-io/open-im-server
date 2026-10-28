package io.openware.platform.tenant.domain.app.repository;

import io.openware.platform.tenant.domain.app.model.AppInstallation;
import java.util.List;
import java.util.Optional;

/** 租户应用安装仓储。 */
public interface AppInstallationRepository {

  Optional<AppInstallation> findByTenantAndApp(Long tenantId, String appId);

  List<AppInstallation> findByTenant(Long tenantId);

  AppInstallation save(AppInstallation installation);
}
