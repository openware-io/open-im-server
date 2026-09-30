package io.openware.platform.admin.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.currency.Currency;
import io.openware.infrastructure.currency.CurrencyContextHolder;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.admin.infra.CustomerOverviewDomainClient;
import io.openware.platform.admin.infra.TenantIamDomainClient;
import io.openware.platform.admin.infra.TenantOverviewDomainClient;
import io.openware.platform.admin.infra.OrderOverviewDomainClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 总部 BFF 只读总览的范围和失败语义契约。 */
class TenantOverviewControllerTest {
    private final CustomerOverviewDomainClient customerClient = Mockito.mock(CustomerOverviewDomainClient.class);
    private final TenantIamDomainClient tenantClient = Mockito.mock(TenantIamDomainClient.class);
    private final TenantOverviewDomainClient tenantOverviewClient = Mockito.mock(TenantOverviewDomainClient.class);
    private final OrderOverviewDomainClient orderOverviewClient = Mockito.mock(OrderOverviewDomainClient.class);
    private final TenantOverviewController controller = new TenantOverviewController(customerClient, tenantClient);

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
        CurrencyContextHolder.clear();
    }

    @Test
    void requiresTenantHeadquartersContext() {
        TenantContextHolder.set(new TenantContext(100L, null, 11L, 9L, 1, List.of("tenant.overview.view")));

        ApiException error = assertThrows(ApiException.class, () -> controller.overview(null, null, null, null));

        assertEquals("TENANT_SCOPE_REQUIRED", error.getCode());
    }

    @Test
    void requiresOverviewPermission() {
        TenantContextHolder.set(new TenantContext(100L, null, null, 9L, 1, List.of()));

        ApiException error = assertThrows(ApiException.class, () -> controller.overview(null, null, null, null));

        assertEquals("PERMISSION_DENIED", error.getCode());
    }

    @Test
    void acceptsBusinessTypeForDomainFiltering() {
        TenantContextHolder.set(headquartersContext());
        when(tenantClient.staffStores(headquartersContext())).thenReturn(List.of());
        when(customerClient.overview(null, null, null)).thenReturn(Map.of());
        var response = controller.overview(null, null, "RETAIL", null);
        assertEquals("COMPLETE", response.dataStatus());
    }

    @Test
    void rejectsStoreOutsideTenantScope() {
        TenantContextHolder.set(headquartersContext());
        when(tenantClient.staffStores(headquartersContext())).thenReturn(List.of(new TenantIamDomainClient.StaffStore(11L,
                null, "门店 A")));

        ApiException error = assertThrows(ApiException.class, () -> controller.overview(null, null, null, "12"));

        assertEquals("STORE_SCOPE_FORBIDDEN", error.getCode());
    }

    @Test
    void returnsTenantCurrencyAndCustomerSummary() {
        TenantContext context = headquartersContext();
        TenantContextHolder.set(context);
        CurrencyContextHolder.set(Currency.USD);
        CurrencyContextHolder.set(Currency.CNY);
        when(tenantClient.staffStores(context)).thenReturn(List.of(new TenantIamDomainClient.StaffStore(11L,
                null, "门店 A")));
        when(customerClient.overview("2026-09-01", "2026-09-30", "11"))
                .thenReturn(Map.of("memberCount", 3));

        var response = controller.overview("2026-09-01", "2026-09-30", null, "11");

        assertEquals("CNY", response.currencyCode());
        assertEquals(3, response.customer().get("memberCount"));
    }

    @Test
    void marksDomainTimeoutAsPartialWithoutServingCachedData() {
        TenantOverviewController partialController = new TenantOverviewController(customerClient, tenantClient, null,
                tenantOverviewClient, orderOverviewClient);
        TenantContext context = headquartersContext();
        TenantContextHolder.set(context);
        when(tenantClient.staffStores(context)).thenReturn(List.of());
        when(customerClient.overview(null, null, null))
                .thenThrow(new ApiException(504, "CUSTOMER_OVERVIEW_TIMEOUT", "timeout"));
        when(tenantOverviewClient.overview(null, null)).thenReturn(Map.of("stores", List.of()));
        when(orderOverviewClient.overview(null, null, null, null)).thenReturn(Map.of("rows", List.of()));

        var response = partialController.overview(null, null, null, null);

        assertEquals("PARTIAL", response.dataStatus());
        assertEquals("CUSTOMER_OVERVIEW_TIMEOUT", response.failures().get("customer"));
        assertEquals(Map.of(), response.customer());
    }

    private static TenantContext headquartersContext() {
        return new TenantContext(100L, null, null, 9L, 1, List.of("tenant.overview.view"));
    }
}
