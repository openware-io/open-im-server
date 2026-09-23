package io.openware.platform.admin.infra.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.openware.platform.admin.domain.model.AdminRole;
import jakarta.servlet.http.Cookie;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** SaaS 后台会话鉴权过滤器：放行/401/白名单。 */
class SaaAdminAuthenticationFilterTest {

  private SaaAdminSessionStore sessionStore;
  private SaaAdminAuthenticationFilter filter;

  @BeforeEach
  void setUp() {
    sessionStore = mock(SaaAdminSessionStore.class);
    filter = new SaaAdminAuthenticationFilter(sessionStore);
  }

  @Test
  void validSessionContinuesChain() throws Exception {
    when(sessionStore.findSession("session-1")).thenReturn(Optional.of(
        new AdminContext(1L, "admin", "管理员", AdminRole.SUPER_ADMIN)));

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/backends");
    request.setCookies(new Cookie(AdminSessionCookie.NAME, "session-1"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(200, response.getStatus());
  }

  @Test
  void missingSessionReturns401() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/backends");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(401, response.getStatus());
  }

  @Test
  void invalidSessionReturns401() throws Exception {
    when(sessionStore.findSession("bad")).thenReturn(Optional.empty());

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/backends");
    request.setCookies(new Cookie(AdminSessionCookie.NAME, "bad"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(401, response.getStatus());
  }

  @Test
  void whitelistedLoginEndpointSkipsAuth() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/auth/login");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(200, response.getStatus());
    assertNull(AdminContextHolder.get());
  }

  @Test
  void writeRequestWithoutCsrfTokenReturns403() throws Exception {
    when(sessionStore.findSession("session-1")).thenReturn(Optional.of(
        new AdminContext(1L, "admin", "管理员", AdminRole.SUPER_ADMIN)));
    when(sessionStore.isCsrfTokenValid("session-1", null)).thenReturn(false);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/backends");
    request.setCookies(new Cookie(AdminSessionCookie.NAME, "session-1"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(403, response.getStatus());
  }

  @Test
  void writeRequestWithValidCsrfTokenContinuesChain() throws Exception {
    when(sessionStore.findSession("session-1")).thenReturn(Optional.of(
        new AdminContext(1L, "admin", "管理员", AdminRole.SUPER_ADMIN)));
    when(sessionStore.isCsrfTokenValid("session-1", "csrf-token")).thenReturn(true);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/backends");
    request.setCookies(new Cookie(AdminSessionCookie.NAME, "session-1"));
    request.addHeader("X-CSRF-Token", "csrf-token");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(200, response.getStatus());
  }
}
