package io.openware.common.payment.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlatformTenantLineHandlerTest {

  @Test
  void paymentMethodTableUsesExplicitTenantIdInsteadOfPlatformContext() {
    assertTrue(new PlatformTenantLineHandler().ignoreTable("tnt_payment_method"));
  }
}
