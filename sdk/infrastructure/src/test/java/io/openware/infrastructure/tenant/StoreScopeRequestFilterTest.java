package io.openware.infrastructure.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 门店请求过滤器：空店参数自动注入当前门店，跨店参数直接拒绝。 */
class StoreScopeRequestFilterTest {

    private final StoreScopeRequestFilter filter = new StoreScopeRequestFilter();

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    void injectsCurrentStoreWhenCallerOmitsStoreId() throws Exception {
        TenantContextHolder.set(new TenantContext(100L, 100L, 102L, 1L, 1,
                List.of("report.view"), "STORE", "HOTEL", "Asia/Shanghai"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/reports/operations");
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(req.getParameter("storeId"));

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isEqualTo("102");
    }

    @Test
    void rejectsAnotherStoreBeforeControllerRuns() throws Exception {
        TenantContextHolder.set(new TenantContext(100L, 100L, 102L, 1L, 1,
                List.of("report.view"), "STORE", "HOTEL", "Asia/Shanghai"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/reports/operations");
        request.setParameter("storeId", "100");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> reached = new AtomicReference<>(false);
        FilterChain chain = (req, res) -> reached.set(true);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("STORE_SCOPE_FORBIDDEN");
        assertThat(reached.get()).isFalse();
    }

    @Test
    void tenantScopeCanQueryAllStores() throws Exception {
        TenantContextHolder.set(new TenantContext(100L, 100L, null, 1L, 1,
                List.of("report.view"), "TENANT", "HOTEL", "Asia/Shanghai"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/reports/operations");
        AtomicReference<String> seen = new AtomicReference<>("unset");
        FilterChain chain = (req, res) -> seen.set(req.getParameter("storeId"));

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isNull();
    }
}
