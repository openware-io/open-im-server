package io.openware.platform.customer.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.security.InternalServiceAuthenticationFilter;
import io.openware.platform.customer.application.CustomerOverviewApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 总部 Customer 汇总契约：内部鉴权、租户边界、时间门禁及账本方向不可回退。 */
class InternalCustomerOverviewControllerTest {
    private final CustomerOverviewApplicationService overviewService = Mockito.mock(CustomerOverviewApplicationService.class);
    private final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
    private final InternalCustomerOverviewController controller = new InternalCustomerOverviewController(overviewService, request);

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
    }

    @Test
    void rejectsMissingInternalAuthentication() {
        TenantContextHolder.set(tenantContext());

        ApiException error = assertThrows(ApiException.class, () -> controller.overview(null, null, null));

        assertEquals("INVALID_INTERNAL_SERVICE_AUTHENTICATION", error.getCode());
    }

    @Test
    void rejectsMissingTenantContextAfterInternalAuthentication() {
        authenticated();

        ApiException error = assertThrows(ApiException.class, () -> controller.overview(null, null, null));

        assertEquals("SAAS_CONTEXT_REQUIRED", error.getCode());
    }

    @Test
    void rejectsInvertedTimeRangeBeforeDatabaseQuery() {
        authenticated();
        TenantContextHolder.set(tenantContext());

        ApiException error = assertThrows(ApiException.class,
                () -> controller.overview("2026-09-02", "2026-09-01", null));

        assertEquals("TIME_RANGE_INVALID", error.getCode());
        verify(overviewService, never()).summarize(any(), any(), any());
    }

    @Test
    void rejectsMalformedStoreFilter() {
        authenticated();
        TenantContextHolder.set(tenantContext());

        ApiException error = assertThrows(ApiException.class,
                () -> controller.overview(null, null, "not-a-store"));

        assertEquals("STORE_FILTER_INVALID", error.getCode());
        verify(overviewService, never()).summarize(any(), any(), any());
    }

    @Test
    void aggregatesSignedLedgerChanges() {
        authenticated();
        TenantContextHolder.set(tenantContext());
        when(overviewService.summarize(any(), any(), any())).thenReturn(
                new CustomerOverviewApplicationService.Summary(3L, 80L, 600L, 20L, 150L, java.time.Instant.now()));

        var response = controller.overview("2026-09-01", "2026-09-30", "11,12");

        assertEquals(3L, response.memberCount());
        assertEquals(80L, response.pointsBalance());
        assertEquals(600L, response.walletBalance());
        assertEquals(20L, response.pointsDelta());
        assertEquals(150L, response.walletDelta());
    }

    private void authenticated() {
        when(request.getAttribute(InternalServiceAuthenticationFilter.AUTHENTICATED_ATTRIBUTE)).thenReturn(Boolean.TRUE);
    }

    private static TenantContext tenantContext() {
        return new TenantContext(100L, null, null, 9L, 1, List.of("tenant.overview.view"));
    }
}
