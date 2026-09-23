package io.openware.platform.identity.infra.security;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * Browser credential for the server-side SaaS user session.
 *
 * <p>Cookie shape is transport-aware via the {@code saas.cookie.plain} switch
 * ({@code SAAS_COOKIE_PLAIN}):
 * <ul>
 *   <li>https environments (ACK dev/prod): {@code __Host-} + {@code Secure}
 *       ({@code __Host-saas_c_session} / {@code __Host-saas_b_session}).</li>
 *   <li>plain-http environments (local Kind on a phone WebView/emulator): the
 *       {@code __Host-} prefix REQUIRES {@code Secure}, and Android WebView
 *       silently drops {@code Secure}/{@code __Host-} cookies set over plain
 *       http even on loopback, so the session never sticks. In that mode we
 *       emit non-prefixed, non-Secure aliases
 *       ({@code saas_c_session} / {@code saas_b_session}); HttpOnly + SameSite
 *       still apply.</li>
 * </ul>
 * Readers (gateway, controllers) accept both name families so a cookie written
 * under either shape keeps working.
 */
public final class SaasUserSessionCookie {
  /** Kept as the C-session alias for existing server consumers during v1 rollout. */
  public static final String NAME = "__Host-saas_c_session";
  public static final String C_NAME = NAME;
  public static final String B_NAME = "__Host-saas_b_session";
  /** Plain-http aliases (no {@code __Host-} prefix, no {@code Secure}). */
  public static final String C_PLAIN = "saas_c_session";
  public static final String B_PLAIN = "saas_b_session";
  private static final Duration TTL = Duration.ofHours(8);

  private SaasUserSessionCookie() {}

  public static boolean isHostPrefixed(String cookieName) {
    return cookieName != null && cookieName.startsWith("__Host-");
  }

  public static void set(HttpServletResponse response, String sessionId) {
    set(response, sessionId, C_NAME);
  }

  public static void set(HttpServletResponse response, String sessionId, String cookieName) {
    response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(cookieName, sessionId)
        .httpOnly(true).secure(true).sameSite("Lax").path("/").maxAge(TTL).build().toString());
  }

  /** Plain-http session cookie: HttpOnly + SameSite=Lax, but no Secure and no {@code __Host-} prefix. */
  public static void setPlain(HttpServletResponse response, String sessionId) {
    setPlain(response, sessionId, C_PLAIN);
  }

  public static void setPlain(HttpServletResponse response, String sessionId, String cookieName) {
    response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(cookieName, sessionId)
        .httpOnly(true).sameSite("Lax").path("/").maxAge(TTL).build().toString());
  }

  public static void clear(HttpServletResponse response) {
    clear(response, C_NAME);
  }

  /** Clears a session cookie, keeping the {@code Secure} attribute only for {@code __Host-} names. */
  public static void clear(HttpServletResponse response, String cookieName) {
    ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(cookieName, "")
        .httpOnly(true).sameSite("Lax").path("/").maxAge(Duration.ZERO);
    if (isHostPrefixed(cookieName)) {
      builder = builder.secure(true);
    }
    response.addHeader(HttpHeaders.SET_COOKIE, builder.build().toString());
  }
}
