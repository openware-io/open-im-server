package io.openware.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.security.InternalServiceAuthenticationFilter;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.OrderOverviewApplicationService;
import io.openware.platform.order.domain.overview.OrderOverviewQuery;
import io.openware.platform.order.domain.overview.OrderOverviewRow;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class InternalOrderOverviewControllerTest {
    private final OrderOverviewApplicationService service = Mockito.mock(OrderOverviewApplicationService.class);
    private final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
    private InternalOrderOverviewController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalOrderOverviewController(service, request);
        TenantContextHolder.set(new TenantContext(100L, 10L, null, 99L, 1));
        when(request.getAttribute(InternalServiceAuthenticationFilter.AUTHENTICATED_ATTRIBUTE)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void readsTenantFromSignedContextAndNormalizesFilters() {
        Instant updatedAt = Instant.parse("2026-09-29T08:00:00Z");
        when(service.summarize(any())).thenReturn(new OrderOverviewApplicationService.Summary(List.of(
                new OrderOverviewRow(2001L, "KTV", "USD", 2,
                        new BigDecimal("120.00"), new BigDecimal("100.00"))), updatedAt));

        InternalOrderOverviewController.OrderOverviewResponse response = controller.overview(
                List.of(2002L, 2001L, 2002L), " ktv ", "2026-09-01", "2026-09-30");

        assertEquals(updatedAt, response.updatedAt());
        assertEquals(1, response.rows().size());
        assertEquals("KTV", response.rows().get(0).businessType());
        ArgumentCaptor<OrderOverviewQuery> captor = ArgumentCaptor.forClass(OrderOverviewQuery.class);
        verify(service).summarize(captor.capture());
        OrderOverviewQuery query = captor.getValue();
        assertEquals(100L, query.tenantId());
        assertEquals(List.of(2002L, 2001L), query.storeIds());
        assertEquals("KTV", query.businessType());
        assertEquals("2026-09-01T00:00", query.fromInclusive().toString());
        assertEquals("2026-09-30T23:59:59.999", query.toInclusive().toString());
    }

    @Test
    void rejectsMissingInternalV2Authentication() {
        when(request.getAttribute(InternalServiceAuthenticationFilter.AUTHENTICATED_ATTRIBUTE)).thenReturn(false);

        ApiException failure = assertThrows(ApiException.class,
                () -> controller.overview(null, null, null, null));

        assertEquals(401, failure.getStatus());
        assertEquals("INVALID_INTERNAL_SERVICE_AUTHENTICATION", failure.getCode());
        verify(service, never()).summarize(any());
    }

    @Test
    void rejectsInvalidFiltersBeforeQuery() {
        ApiException storeFailure = assertThrows(ApiException.class,
                () -> controller.overview(List.of(0L), null, null, null));
        assertEquals("STORE_FILTER_INVALID", storeFailure.getCode());

        ApiException typeFailure = assertThrows(ApiException.class,
                () -> controller.overview(null, "KTV-INVALID", null, null));
        assertEquals("BUSINESS_TYPE_FILTER_INVALID", typeFailure.getCode());

        ApiException timeFailure = assertThrows(ApiException.class,
                () -> controller.overview(null, null, "2026-09-30", "2026-09-01"));
        assertEquals("TIME_RANGE_INVALID", timeFailure.getCode());
        verify(service, never()).summarize(any());
    }
}
