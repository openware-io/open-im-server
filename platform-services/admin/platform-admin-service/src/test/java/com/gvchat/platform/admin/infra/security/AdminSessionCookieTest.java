package com.gvchat.platform.admin.infra.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminSessionCookieTest {

  @Test
  void plainHttpCookieOmitsSecureAttribute() {
    MockHttpServletResponse response = new MockHttpServletResponse();

    AdminSessionCookie.set(response, "session-1", Duration.ofHours(2), true);

    String cookie = response.getHeader("Set-Cookie");
    assertTrue(cookie.contains("saas_admin_session=session-1"));
    assertFalse(cookie.contains("Secure"));
  }

  @Test
  void httpsCookieRetainsSecureAttribute() {
    MockHttpServletResponse response = new MockHttpServletResponse();

    AdminSessionCookie.set(response, "session-1", Duration.ofHours(2), false);

    assertTrue(response.getHeader("Set-Cookie").contains("Secure"));
  }
}
