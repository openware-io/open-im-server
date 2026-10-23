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
import io.openware.platform.tenant.domain.iam.repository.RoleBindingRepository;
import io.openware.platform.tenant.domain.membership.Membership;
import io.openware.platform.tenant.domain.membership.MembershipRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MembershipApplicationServiceTest {

  @Mock private MembershipRepository repository;
  @Mock private RoleBindingRepository bindings;
  @Mock private PermissionSnapshotProvider snapshots;
  @Mock private AuditClient audit;
  private MembershipApplicationService service;

  @BeforeEach
  void setUp() {
    service = new MembershipApplicationService(repository, bindings, snapshots, audit);
    TenantContextHolder.set(new TenantContext(100L, 10L, null, 1L, 1));
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  @Test
  void createUsesCurrentTenant() {
    when(repository.findByAccountAndTenantAndType(9L, 100L, "STAFF")).thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(invocation -> {
      Membership m = invocation.getArgument(0);
      return new Membership(1L, m.accountId(), m.tenantId(), m.organizationId(),
          m.membershipType(), m.memberNo(), m.authorizationVersion(), m.status());
    });

    Membership created = service.create(9L, 10L, "STAFF", "E001");

    assertEquals(100L, created.tenantId());
    assertEquals(9L, created.accountId());
    assertEquals("E001", created.memberNo());
    verify(audit).recordAsync(any());
  }

  @Test
  void duplicateMembershipRejected() {
    when(repository.findByAccountAndTenantAndType(9L, 100L, "STAFF"))
        .thenReturn(Optional.of(Membership.create(9L, 100L, 0L, "STAFF", "E001")));

    assertThrows(ApiException.class, () -> service.create(9L, 0L, "STAFF", "E001"));
  }

  @Test
  void crossTenantAccountLookupDoesNotRequireTenantContext() {
    TenantContextHolder.clear();
    when(repository.findAllByAccountId(9L))
        .thenReturn(List.of(Membership.create(9L, 100L, 0L, "STAFF", "E001")));

    assertEquals(1, service.listByAccount(9L).size());
  }

  @Test
  void statusChangeRejectsOtherTenant() {
    when(repository.findById(7L))
        .thenReturn(Optional.of(Membership.create(9L, 200L, 0L, "STAFF", "E001")));

    assertThrows(ApiException.class, () -> service.changeStatus(7L, "LEFT"));
  }

  @Test
  void createWithoutTenantContextRejected() {
    TenantContextHolder.clear();
    assertThrows(ApiException.class, () -> service.create(9L, 0L, "STAFF", "E001"));
  }

  @Test
  void platformContextCannotCreateTenantMembership() {
    TenantContextHolder.set(new TenantContext(0L, null, null, 1L, 1, List.of(), "PLATFORM"));
    assertThrows(ApiException.class, () -> service.create(9L, 0L, "STAFF", "E001"));
  }
}
