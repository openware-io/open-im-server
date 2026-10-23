package io.openware.platform.tenant.domain.iam.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RoleBindingTest {

  @Test
  void createStoreBinding() {
    RoleBinding binding = RoleBinding.create(5L, 7L, "STORE", 100L, 10L, 20L);
    assertEquals(5L, binding.membershipId());
    assertEquals("STORE", binding.scopeType());
    assertEquals(1, binding.authorizationVersion());
    assertEquals("ACTIVE", binding.status());
  }

  @Test
  void storeScopeRequiresStore() {
    assertThrows(IllegalArgumentException.class,
        () -> RoleBinding.create(5L, 7L, "STORE", 100L, 10L, null));
  }

  @Test
  void organizationScopeRequiresOrganization() {
    assertThrows(IllegalArgumentException.class,
        () -> RoleBinding.create(5L, 7L, "ORGANIZATION", 100L, null, null));
  }

  @Test
  void revokeIncrementsVersion() {
    RoleBinding revoked = RoleBinding.create(5L, 7L, "TENANT", 100L, null, null).revoke();
    assertEquals("REVOKED", revoked.status());
    assertEquals(2, revoked.authorizationVersion());
  }

  @Test
  void invalidScopeRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> RoleBinding.create(5L, 7L, "PLATFORM", 100L, null, null));
  }

  @Test
  void tenantScopeRejectsOrganization() {
    assertThrows(IllegalArgumentException.class,
        () -> RoleBinding.create(5L, 7L, "TENANT", 100L, 10L, null));
  }

  @Test
  void selfScopeAcceptsZeroOrganizationAndStore() {
    RoleBinding binding = RoleBinding.create(5L, 7L, "SELF", 100L, 0L, 0L);
    assertEquals("SELF", binding.scopeType());
  }

  @Test
  void storeScopeRejectsZeroStore() {
    assertThrows(IllegalArgumentException.class,
        () -> RoleBinding.create(5L, 7L, "STORE", 100L, 0L, 0L));
  }
}
