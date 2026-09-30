package io.openware.platform.tenant.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.security.InternalServiceAuthenticationFilter;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.application.TenantOverviewApplicationService;
import io.openware.platform.tenant.infra.persistence.po.StorePo;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class InternalTenantOverviewControllerTest {
  private final TenantOverviewApplicationService service = Mockito.mock(TenantOverviewApplicationService.class);
  private final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
  private final InternalTenantOverviewController controller =
      new InternalTenantOverviewController(service, request);

  @AfterEach
  void clearContext() { TenantContextHolder.clear(); }

  @Test
  void rejectsUnauthenticatedRequest() {
    TenantContextHolder.set(new TenantContext(100L, null, null, 9L, 1));
    ApiException error = assertThrows(ApiException.class, () -> controller.overview(null, null));
    assertEquals("INVALID_INTERNAL_SERVICE_AUTHENTICATION", error.getCode());
    verify(service, never()).stores(Mockito.anyLong(), Mockito.any(), Mockito.any());
  }

  @Test
  void rejectsMissingTenantContext() {
    authenticated();
    ApiException error = assertThrows(ApiException.class, () -> controller.overview(null, null));
    assertEquals("SAAS_CONTEXT_REQUIRED", error.getCode());
  }

  @Test
  void rejectsInvalidStoreFilter() {
    authenticated();
    TenantContextHolder.set(tenantContext());
    ApiException error = assertThrows(ApiException.class, () -> controller.overview("11,0", null));
    assertEquals("STORE_FILTER_INVALID", error.getCode());
  }

  @Test
  void returnsTenantScopedStoresAndBusinessType() {
    authenticated();
    TenantContextHolder.set(tenantContext());
    StorePo store = new StorePo();
    store.setId(11L);
    store.setCode("S11");
    store.setName("Main");
    store.setBusinessType("RETAIL");
    store.setStatus("ACTIVE");
    when(service.stores(100L, List.of(11L), "retail")).thenReturn(List.of(store));

    var response = controller.overview("11", "retail");

    assertEquals(100L, response.tenantId());
    assertEquals(1, response.stores().size());
    assertEquals("RETAIL", response.stores().get(0).businessType());
    verify(service).stores(100L, List.of(11L), "retail");
  }

  private void authenticated() {
    when(request.getAttribute(InternalServiceAuthenticationFilter.AUTHENTICATED_ATTRIBUTE)).thenReturn(Boolean.TRUE);
  }

  private static TenantContext tenantContext() {
    return new TenantContext(100L, null, null, 9L, 1);
  }
}
