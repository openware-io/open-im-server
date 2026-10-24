package io.openware.platform.identity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.openware.platform.identity.infra.client.TenantServiceClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServiceEntryApplicationServiceTest {

  private final TenantServiceClient tenantClient = mock(TenantServiceClient.class);
  private final ServiceEntryApplicationService service = new ServiceEntryApplicationService(tenantClient);

  @Test
  void onlyInstalledAndEntitledAppsAreReturned() {
    when(tenantClient.installedApps(100L)).thenReturn(List.of(
        new TenantServiceClient.InstalledApp(1L, 100L, "saas-hotel-c", "C", "ACTIVE"),
        new TenantServiceClient.InstalledApp(2L, 100L, "saas-ktv-c", "C", "ACTIVE")));
    when(tenantClient.entitlements(100L)).thenReturn(List.of(
        new TenantServiceClient.Entitlement("HOTEL", "ACTIVE", "MANUAL")));

    var entries = service.entries(100L, "C");

    assertEquals(1, entries.size());
    assertEquals("saas-hotel-c", entries.get(0).appId());
    assertEquals("HOTEL", entries.get(0).featureCode());
  }

  @Test
  void otherClientFamilyIsExcluded() {
    when(tenantClient.installedApps(100L)).thenReturn(List.of(
        new TenantServiceClient.InstalledApp(1L, 100L, "saas-hotel-b", "B", "ACTIVE")));
    when(tenantClient.entitlements(100L)).thenReturn(List.of());

    assertEquals(0, service.entries(100L, "C").size());
  }
}
