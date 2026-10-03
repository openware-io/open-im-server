package io.openware.platform.order.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.security.InternalServiceAuthenticationFilter;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.application.VoidRuleApplicationService;
import io.openware.platform.order.infra.persistence.po.OrdVoidRuleConfigPo;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class InternalVoidRuleControllerTest {
    private final VoidRuleApplicationService service = Mockito.mock(VoidRuleApplicationService.class);
    private final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
    private InternalVoidRuleController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalVoidRuleController(service, request);
        TenantContextHolder.set(new TenantContext(100L, 10L, 7L, 99L, 1));
        when(request.getAttribute(InternalServiceAuthenticationFilter.AUTHENTICATED_ATTRIBUTE)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void readsAndWritesUsingSignedTenantContext() {
        when(service.resolve(100L, "KTV", 7L))
                .thenReturn(new VoidRuleApplicationService.RuleView(true, "STORE", 1, 8L));
        assertEquals("STORE", controller.get(7L, "KTV").source());

        OrdVoidRuleConfigPo saved = new OrdVoidRuleConfigPo();
        saved.setId(8L);
        when(service.save(eq(100L), any())).thenReturn(saved);
        assertEquals(8L, controller.save(new InternalVoidRuleController.SaveRequest(
                7L, "KTV", true, 1, "void-rule-1")).getId());

        ArgumentCaptor<VoidRuleApplicationService.SaveCommand> command =
                ArgumentCaptor.forClass(VoidRuleApplicationService.SaveCommand.class);
        verify(service).save(eq(100L), command.capture());
        assertEquals("KTV", command.getValue().businessType());
        assertEquals(7L, command.getValue().storeId());
        verify(service).resolve(100L, "KTV", 7L);
    }

    @Test
    void rejectsStoreOutsideSignedScope() {
        ApiException failure = assertThrows(ApiException.class, () -> controller.save(
                new InternalVoidRuleController.SaveRequest(8L, "KTV", true, 0, "void-rule-2")));

        assertEquals(403, failure.getStatus());
        assertEquals("STORE_SCOPE_FORBIDDEN", failure.getCode());
        verify(service, never()).save(any(), any());
    }

    @Test
    void rejectsMissingInternalAuthentication() {
        when(request.getAttribute(InternalServiceAuthenticationFilter.AUTHENTICATED_ATTRIBUTE)).thenReturn(false);

        ApiException failure = assertThrows(ApiException.class, () -> controller.get(7L, "KTV"));

        assertEquals(401, failure.getStatus());
        assertEquals("INVALID_INTERNAL_SERVICE_AUTHENTICATION", failure.getCode());
        verify(service, never()).resolve(any(), any(), any());
    }
}
