package io.openware.platform.tenant.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.domain.app.model.AppInstallation;
import io.openware.platform.tenant.domain.app.repository.AppInstallationRepository;
import io.openware.platform.tenant.infrastructure.client.IdentityAppCatalogClient;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AppInstallationApplicationServiceTest {

  @Mock private AppInstallationRepository repository;
  @Mock private AuditClient audit;
  @Mock private IdentityAppCatalogClient catalog;
  private AppInstallationApplicationService service;

  @BeforeEach
  void setUp() {
    service = new AppInstallationApplicationService(repository, audit, catalog);
    TenantContextHolder.set(new TenantContext(100L, 0L, 0L, 7L, 1,
        List.of("tenant.tenant.manage"), "TENANT", null, null));
  }

  @AfterEach
  void clear() {
    TenantContextHolder.clear();
  }

  @Test
  void installCreatesActiveInstallation() {
    when(catalog.get("saas-hotel-c")).thenReturn(new IdentityAppCatalogClient.AppInfo("saas-hotel-c", "C", "ACTIVE"));
    when(repository.findByTenantAndApp(100L, "saas-hotel-c")).thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    AppInstallation installed = service.install("saas-hotel-c", "C");

    assertEquals("ACTIVE", installed.status());
    assertEquals("C", installed.clientType());
    verify(audit).recordAsync(any());
  }

  @Test
  void installRejectsClientTypeMismatch() {
    when(catalog.get("saas-hotel-c"))
        .thenReturn(new IdentityAppCatalogClient.AppInfo("saas-hotel-c", "C", "ACTIVE"));

    assertThrows(ApiException.class, () -> service.install("saas-hotel-c", "B"));
  }

  @Test
  void uninstallMarksUninstalled() {
    AppInstallation existing = new AppInstallation(3L, 100L, "saas-hotel-c", "C", "ACTIVE",
        7L, LocalDateTime.now());
    when(repository.findByTenantAndApp(100L, "saas-hotel-c")).thenReturn(Optional.of(existing));
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.uninstall("saas-hotel-c");

    verify(repository).save(any());
    verify(audit).recordAsync(any());
  }

  @Test
  void uninstallMissingFails() {
    when(repository.findByTenantAndApp(100L, "none")).thenReturn(Optional.empty());
    assertThrows(ApiException.class, () -> service.uninstall("none"));
  }
}
