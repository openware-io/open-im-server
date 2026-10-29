package io.openware.platform.tenant.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.domain.iam.model.Role;
import io.openware.platform.tenant.domain.iam.repository.PlatformRoleBindingRepository;
import io.openware.platform.tenant.domain.iam.repository.RoleRepository;
import io.openware.platform.tenant.infrastructure.client.IdentityAccountClient;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlatformOperatorApplicationServiceTest {

  @Mock private IdentityAccountClient accounts;
  @Mock private RoleRepository roles;
  @Mock private PlatformRoleBindingRepository bindings;
  private PlatformOperatorApplicationService service;

  @BeforeEach
  void setUp() {
    service = new PlatformOperatorApplicationService(accounts, roles, bindings);
    TenantContextHolder.set(new TenantContext(0L, 0L, 0L, 1L, 1,
        List.of("platform.iam.role.view"), "PLATFORM", null, null));
  }

  @AfterEach
  void clear() {
    TenantContextHolder.clear();
  }

  @Test
  void listsOperatorsWithRoleBindings() {
    when(accounts.listPlatformOperators()).thenReturn(List.of(
        new IdentityAccountClient.PlatformOperatorAccount(900L, "ops@example.com", "ACTIVE")));
    when(roles.listPlatformRoles()).thenReturn(List.of(
        new Role(5L, null, "platform.operator", "平台运营", "PRESET", "PLATFORM",
            "core", null, "ACTIVE")));
    when(bindings.findPlatformBindingsByAccount(900L)).thenReturn(List.of(
        new PlatformRoleBindingRepository.PlatformBinding(11L, 5L)));

    var operators = service.list();

    assertEquals(1, operators.size());
    assertEquals("ops@example.com", operators.get(0).loginIdentifier());
    assertEquals("platform.operator", operators.get(0).roles().get(0).roleCode());
  }

  @Test
  void requiresPlatformViewPermission() {
    TenantContextHolder.set(new TenantContext(0L, 0L, 0L, 1L, 1, List.of(),
        "PLATFORM", null, null));

    assertThrows(ApiException.class, () -> service.list());
  }
}
