package io.openware.common.payment.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.common.payment.application.CollectApplicationService;
import io.openware.common.payment.application.PaymentOverviewApplicationService;
import io.openware.infrastructure.security.InternalServiceAuthenticationFilter;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class InternalPaymentQueryControllerTest {
  private final CollectApplicationService collectService = Mockito.mock(CollectApplicationService.class);
  private final PaymentOverviewApplicationService overviewService = Mockito.mock(PaymentOverviewApplicationService.class);
  private final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
  private final InternalPaymentQueryController controller =
      new InternalPaymentQueryController(collectService, overviewService, request);

  @AfterEach
  void clearContext() {
    TenantContextHolder.clear();
  }

  @Test
  void rejectsMissingInternalAuthentication() {
    TenantContextHolder.set(new TenantContext(100L, null, null, 9L, 1));
    ApiException error = assertThrows(ApiException.class, () -> controller.overview(null, null, null));
    assertEquals("INVALID_INTERNAL_SERVICE_AUTHENTICATION", error.getCode());
    verify(overviewService, never()).query(Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any());
  }

  @Test
  void rejectsInvertedTimeRangeBeforeDatabaseQuery() {
    authenticated();
    TenantContextHolder.set(new TenantContext(100L, null, null, 9L, 1));
    ApiException error = assertThrows(ApiException.class,
        () -> controller.overview("2026-09-02", "2026-09-01", null));
    assertEquals("TIME_RANGE_INVALID", error.getCode());
    verify(overviewService, never()).query(Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any());
  }

  @Test
  void rejectsInvalidStoreFilter() {
    authenticated();
    TenantContextHolder.set(new TenantContext(100L, null, null, 9L, 1));
    ApiException error = assertThrows(ApiException.class,
        () -> controller.overview(null, null, List.of(11L, 0L)));
    assertEquals("STORE_FILTER_INVALID", error.getCode());
  }

  @Test
  void queriesPaymentDomainWithTenantAndStoreScope() {
    authenticated();
    TenantContextHolder.set(new TenantContext(100L, null, null, 9L, 1));
    PaymentOverviewApplicationService.PaymentOverviewRow row =
        new PaymentOverviewApplicationService.PaymentOverviewRow("CASH", "USD", 2L,
            java.math.BigDecimal.ZERO);
    when(overviewService.query(Mockito.eq(100L), Mockito.eq(List.of(11L)),
        Mockito.any(), Mockito.any())).thenReturn(List.of(row));

    var response = controller.overview("2026-09-01", "2026-09-30", List.of(11L));

    assertEquals(1, response.rows().size());
    assertEquals("CASH", response.rows().get(0).provider());
    verify(overviewService).query(Mockito.eq(100L), Mockito.eq(List.of(11L)),
        Mockito.any(), Mockito.any());
  }

  private void authenticated() {
    when(request.getAttribute(InternalServiceAuthenticationFilter.AUTHENTICATED_ATTRIBUTE)).thenReturn(Boolean.TRUE);
  }
}
