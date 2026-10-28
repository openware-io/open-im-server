package io.openware.platform.tenant.domain.app.model;

import java.time.LocalDateTime;

/** 租户应用安装事实；应用元数据仍以 IM open_application 为准。 */
public record AppInstallation(
    Long id,
    Long tenantId,
    String appId,
    String clientType,
    String status,
    Long installedBy,
    LocalDateTime installedAt) {

  public static final String STATUS_ACTIVE = "ACTIVE";
  public static final String STATUS_UNINSTALLED = "UNINSTALLED";

  public static AppInstallation install(Long tenantId, String appId, String clientType, Long installedBy) {
    if (tenantId == null || tenantId <= 0 || appId == null || appId.isBlank()) {
      throw new IllegalArgumentException("tenantId and appId are required");
    }
    if (!"C".equals(clientType) && !"B".equals(clientType)) {
      throw new IllegalArgumentException("clientType must be C or B");
    }
    return new AppInstallation(null, tenantId, appId.trim(), clientType, STATUS_ACTIVE,
        installedBy == null ? 0L : installedBy, LocalDateTime.now());
  }

  public boolean active() {
    return STATUS_ACTIVE.equals(status);
  }

  public AppInstallation uninstall() {
    return new AppInstallation(id, tenantId, appId, clientType, STATUS_UNINSTALLED,
        installedBy, installedAt);
  }
}
