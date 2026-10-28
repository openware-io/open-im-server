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
import io.openware.platform.tenant.domain.membership.MembershipRepository;
import io.openware.platform.tenant.infrastructure.client.IdentityAccountClient;
import io.openware.platform.tenant.domain.iam.repository.PlatformRoleBindingRepository;
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

  @Mock private PlatformRoleBindingRepository bindings;
  @Mock private RoleRepository roles;
  @Mock private MembershipRepository memberships;
  @Mock private IdentityAccountClient accounts;
  @Mock private AuditClient audit;
  private PlatformRoleBindingApplicationService service;

  @BeforeEach
  void setUp() {
    service = new PlatformRoleBindingApplicationService(bindings, roles, memberships, accounts, audit);
    TenantContextHolder.set(new TenantContext(0L, 0L, 0L, 1L, 1,
        List.of("platform.iam.role.manage"), "PLATFORM", null, null));
  }

  @AfterEach
  void clear() {
    TenantContextHolder.clear();
  }

  @Test
  void assignWritesAccountPlatformBinding() {
    when(accounts.get(900L)).thenReturn(new IdentityAccountClient.AccountInfo(900L, "PLATFORM_OPERATOR", "ACTIVE"));
    when(memberships.findAllByAccountId(900L)).thenReturn(List.of());
    when(roles.findById(5L)).thenReturn(Optional.of(
        new Role(5L, null, "platform.operator", "平台运营", "PLATFORM", "PLATFORM",
            "core", null, "ACTIVE")));
    when(bindings.findPlatformId(900L, 5L)).thenReturn(null, 11L);

    assertEquals(11L, service.assign(900L, 5L));
    verify(bindings).insertPlatform(900L, 5L);
    verify(audit).recordAsync(any());
  }

  @Test
  void nonPlatformRoleRejected() {
    when(accounts.get(900L)).thenReturn(new IdentityAccountClient.AccountInfo(900L, "PLATFORM_OPERATOR", "ACTIVE"));
    when(memberships.findAllByAccountId(900L)).thenReturn(List.of());
    when(roles.findById(6L)).thenReturn(Optional.of(
        new Role(6L, 100L, "tenant.owner", "老板", "TENANT", "TENANT", "core", null, "ACTIVE")));

    assertThrows(ApiException.class, () -> service.assign(900L, 6L));
  }

  @Test
  void tenantUserCannotReceivePlatformRole() {
    when(accounts.get(113L)).thenReturn(new IdentityAccountClient.AccountInfo(113L, "USER", "ACTIVE"));

    assertThrows(ApiException.class, () -> service.assign(113L, 1L));
  }

  @Test
  void accountWithTenantMembershipCannotReceivePlatformRole() {
    when(accounts.get(113L)).thenReturn(new IdentityAccountClient.AccountInfo(113L, "PLATFORM_OPERATOR", "ACTIVE"));
    when(memberships.findAllByAccountId(113L)).thenReturn(List.of(
        new io.openware.platform.tenant.domain.membership.Membership(
            4L, 113L, 100L, 100L, "STAFF", "E113", 1, "ACTIVE")));

    assertThrows(ApiException.class, () -> service.assign(113L, 1L));
  }

  @Test
  void missingPermissionRejected() {
    TenantContextHolder.set(new TenantContext(0L, 0L, 0L, 1L, 1, List.of(),
        "PLATFORM", null, null));

    assertThrows(ApiException.class, () -> service.assign(900L, 5L));
  }
}
