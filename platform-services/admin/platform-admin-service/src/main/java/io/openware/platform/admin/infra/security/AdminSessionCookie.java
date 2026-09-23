package io.openware.platform.admin.infra.security;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/** SaaS 后台会话 Cookie（HttpOnly，服务端 Redis 会话的浏览器凭证）。 */
public final class AdminSessionCookie {

  public static final String NAME = "saas_admin_session";

  private AdminSessionCookie() {}

  public static void set(HttpServletResponse response, String sessionId) {
    set(response, sessionId, SessionTtl.DEFAULT);
  }

  public static void set(HttpServletResponse response, String sessionId, Duration ttl) {
    set(response, sessionId, ttl, false);
  }

  /**
   * Sets the admin session cookie according to the transport used by the environment.
   * HTTPS (ACK) keeps Secure; plain HTTP (local Kind) must omit Secure or browsers drop it.
   */
  public static void set(HttpServletResponse response, String sessionId, Duration ttl, boolean plainHttp) {
    ResponseCookie cookie = ResponseCookie.from(NAME, sessionId)
        .httpOnly(true).secure(!plainHttp).sameSite("Lax").path("/")
        .maxAge(ttl).build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }

  public static void clear(HttpServletResponse response) {
    clear(response, false);
  }

  public static void clear(HttpServletResponse response, boolean plainHttp) {
    ResponseCookie cookie = ResponseCookie.from(NAME, "")
        .httpOnly(true).secure(!plainHttp).sameSite("Lax").path("/")
        .maxAge(Duration.ZERO).build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }
}
