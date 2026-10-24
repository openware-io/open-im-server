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
import io.openware.platform.tenant.domain.iam.model.Role;
import io.openware.platform.tenant.domain.iam.repository.RoleRepository;
import io.openware.platform.tenant.infra.persistence.mapper.RoleBindingMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlatformRoleBindingApplicationServiceTest {

  @Mock private RoleBindingMapper mapper;
  @Mock private RoleRepository roles;
  @Mock private AuditClient audit;
  private PlatformRoleBindingApplicationService service;

  @BeforeEach
  void setUp() {
    service = new PlatformRoleBindingApplicationService(mapper, roles, audit);
    TenantContextHolder.set(new TenantContext(0L, 0L, 0L, 1L, 1,
        List.of("platform.iam.role.manage"), "PLATFORM", null, null));
  }

  @AfterEach
  void clear() {
    TenantContextHolder.clear();
  }

  @Test
  void assignWritesAccountPlatformBinding() {
    when(roles.findById(5L)).thenReturn(Optional.of(
        new Role(5L, null, "platform.operator", "平台运营", "PLATFORM", "PLATFORM",
            "core", null, "ACTIVE")));
    when(mapper.selectPlatformId(900L, 5L)).thenReturn(null, 11L);

    assertEquals(11L, service.assign(900L, 5L));
    verify(mapper).insertPlatform(900L, 5L);
    verify(audit).recordAsync(any());
  }

  @Test
  void nonPlatformRoleRejected() {
    when(roles.findById(6L)).thenReturn(Optional.of(
        new Role(6L, 100L, "tenant.owner", "老板", "TENANT", "TENANT", "core", null, "ACTIVE")));

    assertThrows(ApiException.class, () -> service.assign(900L, 6L));
  }

  @Test
  void missingPermissionRejected() {
    TenantContextHolder.set(new TenantContext(0L, 0L, 0L, 1L, 1, List.of(),
        "PLATFORM", null, null));

    assertThrows(ApiException.class, () -> service.assign(900L, 5L));
  }
}
