package io.openware.platform.identity.infra.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** Cookie 形态随环境切换：https 用 __Host-+Secure，纯 http 用无前缀非 Secure 别名。 */
class SaasUserSessionCookieTest {

    private static String setCookieHeader(MockHttpServletResponse response) {
        String header = response.getHeader("Set-Cookie");
        assertNotNull(header);
        return header;
    }

    @Test
    void defaultShapeKeepsHostPrefixAndSecure() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SaasUserSessionCookie.set(response, "sid-1", SaasUserSessionCookie.C_NAME);
        String header = setCookieHeader(response);
        assertTrue(header.contains("__Host-saas_c_session=sid-1"));
        assertTrue(header.contains("Secure"));
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Lax"));
        assertTrue(header.contains("Path=/"));
    }

    @Test
    void plainShapeDropsHostPrefixAndSecure() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SaasUserSessionCookie.setPlain(response, "sid-2", SaasUserSessionCookie.C_PLAIN);
        String header = setCookieHeader(response);
        assertTrue(header.contains("saas_c_session=sid-2"));
        assertFalse(header.contains("Secure"), "plain-http cookie must not carry Secure");
        assertFalse(header.contains("__Host-"), "plain-http cookie must not carry __Host- prefix");
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Lax"));
        assertTrue(header.contains("Path=/"));
    }

    @Test
    void clearKeepsSecureOnlyForHostPrefixedName() {
        MockHttpServletResponse hostResponse = new MockHttpServletResponse();
        SaasUserSessionCookie.clear(hostResponse, SaasUserSessionCookie.C_NAME);
        assertTrue(setCookieHeader(hostResponse).contains("Secure"));

        MockHttpServletResponse plainResponse = new MockHttpServletResponse();
        SaasUserSessionCookie.clear(plainResponse, SaasUserSessionCookie.C_PLAIN);
        assertFalse(setCookieHeader(plainResponse).contains("Secure"));
    }

    @Test
    void plainAliasNamesAreDistinctFromHostNames() {
        assertTrue(SaasUserSessionCookie.isHostPrefixed(SaasUserSessionCookie.C_NAME));
        assertTrue(SaasUserSessionCookie.isHostPrefixed(SaasUserSessionCookie.B_NAME));
        assertFalse(SaasUserSessionCookie.isHostPrefixed(SaasUserSessionCookie.C_PLAIN));
        assertFalse(SaasUserSessionCookie.isHostPrefixed(SaasUserSessionCookie.B_PLAIN));
        assertEquals("saas_c_session", SaasUserSessionCookie.C_PLAIN);
        assertEquals("saas_b_session", SaasUserSessionCookie.B_PLAIN);
    }
}
