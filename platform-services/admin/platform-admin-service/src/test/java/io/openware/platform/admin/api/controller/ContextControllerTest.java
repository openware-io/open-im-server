package io.openware.platform.admin.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.platform.admin.api.context.ContextDtos.SelectContextRequest;
import io.openware.platform.admin.domain.model.AdminRole;
import io.openware.platform.admin.infra.TenantIamDomainClient;
import io.openware.platform.admin.infra.security.AdminContext;
import io.openware.platform.admin.infra.security.AdminContextHolder;
import io.openware.platform.admin.infra.security.AdminTenantContextTokenSigner;
import io.openware.platform.admin.infra.security.SaaAdminSessionStore;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextControllerTest {
    @AfterEach
    void cleanup() { AdminContextHolder.clear(); }

    @Test
    void savesAuthorizedSelectionAndRejectsUnknownTenant() {
        var iam = mock(TenantIamDomainClient.class);
        var signer = mock(AdminTenantContextTokenSigner.class);
        var sessions = mock(SaaAdminSessionStore.class);
        var controller = new ContextController(iam, signer, sessions);
        AdminContextHolder.set(new AdminContext(1L, "admin", "admin", AdminRole.SUPER_ADMIN, 1L, null));
        when(iam.contexts(1L)).thenReturn(List.of(new TenantIamDomainClient.Context(
                "100::", 100L, "A380", null, null, null, null, List.of(), "TENANT")));
        when(iam.permissions(1L, 100L, null, null, true)).thenReturn(new TenantIamDomainClient.PermissionSnapshot(
                1L, 100L, null, null, 1, List.of("iam.role.manage")));
        when(signer.sign(1L, 100L, null, null, 1, List.of("iam.role.manage"), "TENANT", "USD")).thenReturn("signed");
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie("saas_admin_session", "test-session"));
        assertEquals(100L, controller.select(new SelectContextRequest("100::"), request).tenantId());
        verify(sessions).setActiveContext("test-session", "signed", "100::");
        assertThrows(ApiException.class, () -> controller.select(new SelectContextRequest("200::"), request));
        verifyNoMoreInteractions(sessions);
    }

    /** 目标租户配置为 CNY 时：token claim 与响应体都是 CNY。 */
    @Test
    void signsAndReturnsCnyWhenTargetTenantIsConfiguredAsCny() {
        var iam = mock(TenantIamDomainClient.class);
        var signer = mock(AdminTenantContextTokenSigner.class);
        var sessions = mock(SaaAdminSessionStore.class);
        var controller = new ContextController(iam, signer, sessions);
        AdminContextHolder.set(new AdminContext(1L, "admin", "admin", AdminRole.SUPER_ADMIN, 1L, null));
        when(iam.contexts(1L)).thenReturn(List.of(new TenantIamDomainClient.Context(
                "100::", 100L, "A380", null, null, null, null, List.of(), "TENANT")));
        when(iam.permissions(1L, 100L, null, null, true)).thenReturn(new TenantIamDomainClient.PermissionSnapshot(
                1L, 100L, null, null, 1, List.of("iam.role.manage")));
        when(iam.tenantCurrencyCode(100L)).thenReturn("CNY");
        when(signer.sign(1L, 100L, null, null, 1, List.of("iam.role.manage"), "TENANT", "CNY")).thenReturn("signed-cny");
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie("saas_admin_session", "test-session"));

        var response = controller.select(new SelectContextRequest("100::"), request);

        assertEquals("CNY", response.currencyCode());
        verify(sessions).setActiveContext("test-session", "signed-cny", "100::");
    }

    /** 未配置（或查询失败/未知值）时签发 USD，且不得让上下文选择失败。 */
    @Test
    void signsAndReturnsUsdWhenTargetTenantHasNoCurrencyConfig() {
        var iam = mock(TenantIamDomainClient.class);
        var signer = mock(AdminTenantContextTokenSigner.class);
        var sessions = mock(SaaAdminSessionStore.class);
        var controller = new ContextController(iam, signer, sessions);
        AdminContextHolder.set(new AdminContext(1L, "admin", "admin", AdminRole.SUPER_ADMIN, 1L, null));
        when(iam.contexts(1L)).thenReturn(List.of(new TenantIamDomainClient.Context(
                "100::", 100L, "A380", null, null, null, null, List.of(), "TENANT")));
        when(iam.permissions(1L, 100L, null, null, true)).thenReturn(new TenantIamDomainClient.PermissionSnapshot(
                1L, 100L, null, null, 1, List.of("iam.role.manage")));
        when(iam.tenantCurrencyCode(100L)).thenReturn("RMB");
        when(signer.sign(1L, 100L, null, null, 1, List.of("iam.role.manage"), "TENANT", "USD")).thenReturn("signed-usd");
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie("saas_admin_session", "test-session"));

        var response = controller.select(new SelectContextRequest("100::"), request);

        assertEquals("USD", response.currencyCode());
        verify(sessions).setActiveContext("test-session", "signed-usd", "100::");
    }
}
