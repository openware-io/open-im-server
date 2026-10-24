package io.openware.platform.identity.infra.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.openware.common.exception.ApiException;
import org.junit.jupiter.api.Test;

class SaasAppRegistryTest {

  private final SaasAppRegistry registry = new SaasAppRegistry(
      "saas-a380-c,saas-hotel-c", "saas-a380-h5,saas-hotel-b");

  @Test
  void consumerAndBackendAreExplicit() {
    assertEquals(SaasAppRegistry.ClientType.C, registry.requireType("saas-hotel-c"));
    assertEquals(SaasAppRegistry.ClientType.B, registry.requireType("saas-hotel-b"));
  }

  @Test
  void sameAppCannotBeBothFamilies() {
    assertThrows(IllegalStateException.class,
        () -> new SaasAppRegistry("saas-hotel-c", "saas-hotel-c"));
  }

  @Test
  void refererFamilyIsEnforced() {
    registry.requireReferer("saas-hotel-c", "http://localhost/a380/");
    registry.requireReferer("saas-hotel-b", "http://localhost/saas/");
    assertThrows(ApiException.class,
        () -> registry.requireReferer("saas-hotel-b", "http://localhost/a380/"));
    assertThrows(ApiException.class,
        () -> registry.requireReferer("saas-hotel-c", "http://localhost/saas/"));
  }

  @Test
  void unknownAppRejected() {
    assertThrows(ApiException.class, () -> registry.requireType("saas-unknown"));
    assertThrows(ApiException.class, () -> registry.requireType(null));
  }

  @Test
  void cookieFamilyFollowsRegistryNotSuffix() {
    assertEquals(SaasUserSessionCookie.C_PLAIN, registry.cookieName("saas-hotel-c", true));
    assertEquals(SaasUserSessionCookie.B_PLAIN, registry.cookieName("saas-hotel-b", true));
  }
}
