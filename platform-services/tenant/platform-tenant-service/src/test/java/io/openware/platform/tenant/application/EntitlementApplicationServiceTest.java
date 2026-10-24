package io.openware.platform.tenant.application;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.infra.persistence.mapper.EntitlementMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EntitlementApplicationServiceTest {

  @Mock private EntitlementMapper mapper;
  @Mock private AuditClient audit;

  @AfterEach
  void clear() {
    TenantContextHolder.clear();
  }

  @Test
  void tenantCannotGrantCommercialEntitlement() {
    EntitlementApplicationService service = new EntitlementApplicationService(mapper, audit);
    TenantContextHolder.set(new TenantContext(100L, 0L, 0L, 7L, 1,
        List.of("tenant.business.manage"), "TENANT", null, null));

    assertThrows(ApiException.class, () -> service.grant("HOTEL2", "ACTIVE"));
  }

  @Test
  void platformGrantSucceeds() {
    EntitlementApplicationService service = new EntitlementApplicationService(mapper, audit);
    TenantContextHolder.set(new TenantContext(0L, 0L, 0L, 1L, 1,
        List.of("platform.tenant.entitlement.manage"), "PLATFORM", null, null));

    service.grantPlatform(100L, "HOTEL2", "ACTIVE", "MANUAL");

    verify(mapper).upsert(100L, "HOTEL2", "ACTIVE");
    verify(audit).recordAsync(any());
  }

  @Test
  void platformWithoutPermissionFails() {
    EntitlementApplicationService service = new EntitlementApplicationService(mapper, audit);
    TenantContextHolder.set(new TenantContext(0L, 0L, 0L, 1L, 1,
        List.of("platform.tenant.view"), "PLATFORM", null, null));

    assertThrows(ApiException.class, () -> service.grantPlatform(100L, "HOTEL2", "ACTIVE", "MANUAL"));
  }
}
