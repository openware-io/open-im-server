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
import io.openware.platform.tenant.domain.iam.model.BindingDescriptor;
import io.openware.platform.tenant.domain.iam.model.Role;
import io.openware.platform.tenant.domain.iam.model.RoleBinding;
import io.openware.platform.tenant.domain.iam.repository.RoleBindingRepository;
import io.openware.platform.tenant.domain.iam.repository.OrganizationRepository;
import io.openware.platform.tenant.domain.iam.repository.RoleRepository;
import io.openware.platform.tenant.domain.store.repository.StoreRepository;
import io.openware.platform.tenant.domain.membership.Membership;
import io.openware.platform.tenant.domain.membership.MembershipRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoleBindingApplicationServiceTest {

  @Mock private RoleBindingRepository bindings;
  @Mock private MembershipRepository memberships;
  @Mock private RoleRepository roles;
  @Mock private StoreRepository stores;
  @Mock private OrganizationRepository organizations;
  @Mock private PermissionSnapshotProvider snapshots;
  @Mock private AuditClient audit;
  private RoleBindingApplicationService service;

  @BeforeEach
  void setUp() {
    service = new RoleBindingApplicationService(bindings, memberships, roles, stores, organizations, snapshots, audit);
    TenantContextHolder.set(new TenantContext(100L, 10L, null, 1L, 1));
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  @Test
  void assignStoreRole() {
    when(memberships.findById(5L)).thenReturn(Optional.of(
        new Membership(5L, 9L, 100L, 10L, "STAFF", "E001", 1, "ACTIVE")));
    when(roles.findById(7L)).thenReturn(Optional.of(
        new Role(7L, 100L, "store.manager", "店长", "TENANT", "STORE", "hotel", null, "ACTIVE")));
    when(stores.findOwnerTenantId(20L)).thenReturn(Optional.of(100L));
    when(organizations.findTenantId(10L)).thenReturn(Optional.of(100L));
    when(bindings.findActive(5L, 7L, "STORE", 100L, 10L, 20L)).thenReturn(Optional.empty());
    when(bindings.save(any())).thenAnswer(invocation -> {
      RoleBinding b = invocation.getArgument(0);
      return new RoleBinding(1L, b.membershipId(), b.roleId(), b.scopeType(), b.tenantId(),
          b.organizationId(), b.storeId(), b.authorizationVersion(), b.status());
    });

    RoleBinding binding = service.assign(5L, 7L, "STORE", 10L, 20L);

    assertEquals(1L, binding.id());
    assertEquals(5L, binding.membershipId());
    assertEquals(20L, binding.storeId());
    verify(snapshots).evict(9L);
    verify(memberships).save(any(Membership.class));
    verify(audit).recordAsync(any());
  }

  @Test
  void largeTenantIdStoreOwnershipComparesByValue() {
    TenantContextHolder.set(new TenantContext(1000L, 10L, null, 1L, 1));
    when(memberships.findById(5L)).thenReturn(Optional.of(
        new Membership(5L, 9L, 1000L, 10L, "STAFF", "E001", 1, "ACTIVE")));
    when(roles.findById(7L)).thenReturn(Optional.of(
        new Role(7L, 1000L, "store.manager", "店长", "TENANT", "STORE", "hotel", null, "ACTIVE")));
    when(organizations.findTenantId(10L)).thenReturn(Optional.of(1000L));
    when(stores.findOwnerTenantId(20L)).thenReturn(Optional.of(1000L));
    when(bindings.findActive(5L, 7L, "STORE", 1000L, 10L, 20L)).thenReturn(Optional.empty());
    when(bindings.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    assertEquals(1000L, service.assign(5L, 7L, "STORE", 10L, 20L).tenantId());
  }

  @Test
  void updateScopeCannotPromoteStoreToTenant() {
    when(roles.findById(7L)).thenReturn(Optional.of(
        new Role(7L, 100L, "store.manager", "店长", "TENANT", "STORE", "hotel", null, "ACTIVE")));

    assertThrows(ApiException.class,
        () -> service.updateScopeByAccountAndRole(9L, 7L, null, null, "TENANT"));
  }

  @Test
  void updateScopeRejectsOtherTenantStore() {
    when(roles.findById(7L)).thenReturn(Optional.of(
        new Role(7L, 100L, "store.manager", "店长", "TENANT", "STORE", "hotel", null, "ACTIVE")));
    when(organizations.findTenantId(10L)).thenReturn(Optional.of(100L));
    when(stores.findOwnerTenantId(20L)).thenReturn(Optional.of(200L));

    assertThrows(ApiException.class,
        () -> service.updateScopeByAccountAndRole(9L, 7L, 10L, 20L, "STORE"));
  }

  @Test
  void platformAssignmentReturnsExplicitClientError() {
    ApiException error = assertThrows(ApiException.class,
        () -> service.assign(5L, 7L, "PLATFORM", null, null));
    assertEquals("PLATFORM_BINDING_OUT_OF_SCOPE", error.getCode());
  }

  @Test
  void revokeAnyRejectsOtherTenantBinding() {
    when(bindings.findDescriptor(9L)).thenReturn(Optional.of(
        new BindingDescriptor(9L, "MEMBERSHIP", 1L, 200L, "STORE", 3)));

    assertThrows(ApiException.class, () -> service.revokeAny(9L));
  }

  @Test
  void revokeAnyRejectsPlatformBindingFromTenantContext() {
    when(bindings.findDescriptor(9L)).thenReturn(Optional.of(
        new BindingDescriptor(9L, "ACCOUNT", 1L, 0L, "PLATFORM", 3)));

    assertThrows(ApiException.class, () -> service.revokeAny(9L));
  }

  @Test
  void listIdsTenantContextDoesNotExposePlatformBindings() {
    when(memberships.findAllByAccountId(9L)).thenReturn(java.util.List.of());

    assertEquals(0, service.listIdsByAccount(9L).size());
    verify(bindings, org.mockito.Mockito.never()).findPlatformIdsByAccount(9L);
  }

  @Test
  void crossTenantStoreRejected() {
    when(memberships.findById(5L)).thenReturn(Optional.of(
        new Membership(5L, 9L, 100L, 10L, "STAFF", "E001", 1, "ACTIVE")));
    when(roles.findById(7L)).thenReturn(Optional.of(
        new Role(7L, 100L, "store.manager", "店长", "TENANT", "STORE", "hotel", null, "ACTIVE")));
    when(organizations.findTenantId(10L)).thenReturn(Optional.of(100L));
    when(stores.findOwnerTenantId(20L)).thenReturn(Optional.of(200L));

    assertThrows(ApiException.class, () -> service.assign(5L, 7L, "STORE", 10L, 20L));
  }

  @Test
  void crossTenantOrganizationRejected() {
    when(memberships.findById(5L)).thenReturn(Optional.of(
        new Membership(5L, 9L, 100L, 10L, "STAFF", "E001", 1, "ACTIVE")));
    when(roles.findById(7L)).thenReturn(Optional.of(
        new Role(7L, 100L, "org.manager", "组织", "TENANT", "ORGANIZATION", "hotel", null, "ACTIVE")));
    when(organizations.findTenantId(10L)).thenReturn(Optional.of(200L));

    assertThrows(ApiException.class, () -> service.assign(5L, 7L, "ORGANIZATION", 10L, null));
  }

  @Test
  void crossTenantMembershipRejected() {
    when(memberships.findById(5L)).thenReturn(Optional.of(
        new Membership(5L, 9L, 200L, 0L, "STAFF", "E001", 1, "ACTIVE")));

    assertThrows(ApiException.class, () -> service.assign(5L, 7L, "STORE", null, 20L));
  }

  @Test
  void customerMembershipCannotGetBackendRole() {
    when(memberships.findById(5L)).thenReturn(Optional.of(
        new Membership(5L, 9L, 100L, 0L, "CUSTOMER", "C001", 1, "ACTIVE")));

    assertThrows(ApiException.class, () -> service.assign(5L, 7L, "TENANT", null, null));
  }

  @Test
  void roleScopeMismatchRejected() {
    when(memberships.findById(5L)).thenReturn(Optional.of(
        new Membership(5L, 9L, 100L, 0L, "STAFF", "E001", 1, "ACTIVE")));
    when(roles.findById(7L)).thenReturn(Optional.of(
        new Role(7L, 100L, "tenant.owner", "老板", "TENANT", "TENANT", null, null, "ACTIVE")));

    assertThrows(ApiException.class, () -> service.assign(5L, 7L, "STORE", null, 20L));
  }
}
