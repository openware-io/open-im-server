package io.openware.platform.identity.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.platform.identity.application.AuthContextApplicationService;
import io.openware.platform.identity.application.ServiceEntryApplicationService;
import io.openware.platform.identity.infra.security.SaasUserSessionCookie;
import io.openware.platform.identity.infra.security.AccountTokenResolver;
import io.openware.platform.identity.infra.security.SaasAppRegistry;
import io.openware.platform.identity.infra.security.SaasUserSessionStore;
import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthContextControllerTest {
    private static final String C_APP = "saas-c";
    private static final String B_APP = "saas-b";

    private AuthContextController controller(SaasUserSessionStore store) {
        return new AuthContextController(mock(AuthContextApplicationService.class), store,
                mock(AccountTokenResolver.class), new SaasAppRegistry("saas-c,saas-hotel-c,saas-ktv-c", "saas-b,saas-hotel-b,saas-ktv-b"), mock(ServiceEntryApplicationService.class), true);
    }

    private static SaasUserSessionStore.UserSession session(long accountId, String appId) {
        return new SaasUserSessionStore.UserSession(accountId, null, appId);
    }

    @Test
    void csrfResponseDisablesBrowserCaching() {
        SaasUserSessionStore sessionStore = mock(SaasUserSessionStore.class);
        when(sessionStore.issueCsrfToken("session-1")).thenReturn("csrf-1");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(SaasUserSessionCookie.C_PLAIN, "session-1"));
        request.addHeader("Referer", "http://127.0.0.1:30080/c/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller(sessionStore).csrf(C_APP, request, response);

        assertEquals("no-store, no-cache, must-revalidate", response.getHeader("Cache-Control"));
        assertEquals("no-cache", response.getHeader("Pragma"));
    }

    @Test
    void sessionResolvedFromPlainHttpCookieAlias() {
        SaasUserSessionStore sessionStore = mock(SaasUserSessionStore.class);
        when(sessionStore.findSession("session-plain")).thenReturn(
                java.util.Optional.of(session(157L, C_APP)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(SaasUserSessionCookie.C_PLAIN, "session-plain"));
        request.addHeader("Referer", "http://127.0.0.1:30080/c/");

        AuthContextController.SessionResponse response = controller(sessionStore).sessionStatus(C_APP, request);

        assertTrue(response.authenticated());
        assertEquals(157L, response.accountId());
        assertEquals(C_APP, response.appId());
    }

    /**
     * 回归：B 端（运营后台）请求不得因为本机只有 C 端会话就"借用"它。
     *
     * <p>真机曾出现：消费端会话存在时打开 A380后台，页面被判定为已登录而不走 OAuth，
     * 之后所有运营动作都用消费端会话鉴权 → `PERMISSION_DENIED 缺少权限: reservation.confirm`。
     */
    @Test
    void backendAppDoesNotBorrowConsumerSession() {
        SaasUserSessionStore sessionStore = mock(SaasUserSessionStore.class);
        when(sessionStore.findSession("c-session")).thenReturn(java.util.Optional.of(session(133L, C_APP)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(SaasUserSessionCookie.C_PLAIN, "c-session"));

        AuthContextController.SessionResponse response = controller(sessionStore).sessionStatus(B_APP, request);

        assertFalse(response.authenticated(), "B 端请求不得回落到 C 端会话");
    }

    @Test
    void consumerAppDoesNotBorrowBackendSession() {
        SaasUserSessionStore sessionStore = mock(SaasUserSessionStore.class);
        when(sessionStore.findSession("b-session")).thenReturn(java.util.Optional.of(session(133L, B_APP)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(SaasUserSessionCookie.B_PLAIN, "b-session"));

        AuthContextController.SessionResponse response = controller(sessionStore).sessionStatus(C_APP, request);

        assertFalse(response.authenticated(), "C 端请求不得回落到 B 端会话");
    }

    @Test
    void declaredAppResolvesItsOwnFamilySession() {
        SaasUserSessionStore sessionStore = mock(SaasUserSessionStore.class);
        when(sessionStore.findSession("b-session")).thenReturn(java.util.Optional.of(session(133L, B_APP)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        // 两族 cookie 并存时，仍必须选中 B 族。
        request.setCookies(
                new Cookie(SaasUserSessionCookie.C_PLAIN, "c-session"),
                new Cookie(SaasUserSessionCookie.B_PLAIN, "b-session"));
        request.addHeader("Referer", "http://127.0.0.1:30080/saas/");

        AuthContextController.SessionResponse response = controller(sessionStore).sessionStatus(B_APP, request);

        assertTrue(response.authenticated());
        assertEquals(B_APP, response.appId());
    }

    @Test
    void withoutAppIdIsRejected() {
        SaasUserSessionStore sessionStore = mock(SaasUserSessionStore.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(SaasUserSessionCookie.C_PLAIN, "c-session"));

        var error = assertThrows(ApiException.class,
            () -> controller(sessionStore).sessionStatus(null, request));
        assertEquals("SAAS_APP_REQUIRED", error.getCode());
    }
}
