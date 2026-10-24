package io.openware.platform.tenant.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.domain.app.model.AppInstallation;
import io.openware.platform.tenant.domain.app.repository.AppInstallationRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 租户应用安装：只记录安装事实，应用元数据仍由 IM 应用注册表维护。 */
@Service
public class AppInstallationApplicationService {

  private final AppInstallationRepository repository;
  private final AuditClient audit;

  public AppInstallationApplicationService(AppInstallationRepository repository, AuditClient audit) {
    this.repository = repository;
    this.audit = audit;
  }

  public List<AppInstallation> list() {
    return listForTenant(requireTenant().tenantId());
  }

  public List<AppInstallation> listForTenant(Long tenantId) {
    if (tenantId == null || tenantId <= 0) {
      throw new ApiException(400, "TENANT_REQUIRED", "tenantId 不能为空");
    }
    return repository.findByTenant(tenantId);
  }

  @Transactional
  public AppInstallation install(String appId, String clientType) {
    TenantContext context = requireTenant();
    PermissionGuard.require("tenant.tenant.manage");
    if (appId == null || appId.isBlank()) {
      throw new ApiException(400, "APP_ID_REQUIRED", "appId 不能为空");
    }
    String normalized = appId.trim();
    AppInstallation existing = repository.findByTenantAndApp(context.tenantId(), normalized).orElse(null);
    AppInstallation saved;
    if (existing != null) {
      if (existing.active()) {
        return existing;
      }
      saved = repository.save(new AppInstallation(existing.id(), existing.tenantId(), existing.appId(),
          clientType == null ? existing.clientType() : clientType,
          AppInstallation.STATUS_ACTIVE, context.accountId(), existing.installedAt()));
    } else {
      saved = repository.save(AppInstallation.install(context.tenantId(), normalized, clientType,
          context.accountId()));
    }
    audit.recordAsync(AuditClient.AuditRecord.of(context.tenantId(), context.accountId(),
        "iam.app.install", "app_installation", String.valueOf(saved.id()), null,
        "app-install:" + saved.tenantId() + ":" + saved.appId(),
        "{\"appId\":\"" + saved.appId() + "\",\"clientType\":\"" + saved.clientType() + "\"}"));
    return saved;
  }

  @Transactional
  public void uninstall(String appId) {
    TenantContext context = requireTenant();
    PermissionGuard.require("tenant.tenant.manage");
    AppInstallation existing = repository.findByTenantAndApp(context.tenantId(), appId)
        .orElseThrow(() -> new ApiException(404, "APP_NOT_INSTALLED", "应用未安装"));
    repository.save(existing.uninstall());
    audit.recordAsync(AuditClient.AuditRecord.of(context.tenantId(), context.accountId(),
        "iam.app.uninstall", "app_installation", String.valueOf(existing.id()), null,
        "app-uninstall:" + existing.tenantId() + ":" + existing.appId(),
        "{\"appId\":\"" + existing.appId() + "\"}"));
  }

  private static TenantContext requireTenant() {
    TenantContext context = TenantContextHolder.get();
    if (context == null || context.tenantId() <= 0) {
      throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
    }
    return context;
  }
}
