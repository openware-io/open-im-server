package io.openware.platform.identity.application;

import io.openware.platform.identity.infra.client.TenantServiceClient;
import java.util.List;
import org.springframework.stereotype.Service;

/** 服务入口：已安装应用 ∩ 租户权益 ∩ 客户端族。 */
@Service
public class ServiceEntryApplicationService {

  private final TenantServiceClient tenantClient;

  public ServiceEntryApplicationService(TenantServiceClient tenantClient) {
    this.tenantClient = tenantClient;
  }

  public List<ServiceEntry> entries(Long tenantId, String clientType) {
    var entitlements = tenantClient.entitlements(tenantId).stream()
        .filter(item -> "ACTIVE".equals(item.status()))
        .map(TenantServiceClient.Entitlement::featureCode)
        .toList();
    return tenantClient.installedApps(tenantId).stream()
        .filter(app -> "ACTIVE".equals(app.status()))
        .filter(app -> clientType.equals(app.clientType()))
        .filter(app -> featureFor(app.appId()) == null || entitlements.contains(featureFor(app.appId())))
        .map(app -> new ServiceEntry(app.appId(), app.clientType(), featureFor(app.appId())))
        .toList();
  }

  private static String featureFor(String appId) {
    String value = appId == null ? "" : appId.toLowerCase();
    if (value.contains("hotel")) {
      return "HOTEL";
    }
    if (value.contains("ktv")) {
      return "KTV";
    }
    return null;
  }

  public record ServiceEntry(String appId, String clientType, String featureCode) {
  }
}
