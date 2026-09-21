package com.gvchat.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;

class InternalServiceAuthenticationPropertiesTest {
  @Test
  void shouldRejectMissingSecret() {
    InternalServiceAuthenticationProperties properties = validProperties();
    properties.setSecret(null);

    assertThatIllegalStateException().isThrownBy(properties::validate)
        .withMessage("internal.service-auth secret must contain at least 32 characters");
  }

  @Test
  void shouldRejectShortSecret() {
    InternalServiceAuthenticationProperties properties = validProperties();
    properties.setSecret("short-secret");

    assertThatIllegalStateException().isThrownBy(properties::validate)
        .withMessage("internal.service-auth secret must contain at least 32 characters");
  }

  private InternalServiceAuthenticationProperties validProperties() {
    InternalServiceAuthenticationProperties properties = new InternalServiceAuthenticationProperties();
    properties.setServiceName("im-user-service");
    properties.setExpectedSource("im-admin-service");
    properties.setSecret("test-internal-service-authentication-secret");
    return properties;
  }
}
