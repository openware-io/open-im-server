package io.openware.platform.admin.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.admin.domain.repository.ReportQueryRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 门店报表必须锁定当前门店，不能通过空 storeId/跨店参数看到租户下其它业态数据。 */
class AdminReportStoreScopeTest {

    private final ReportQueryRepository repository = mock(ReportQueryRepository.class);
    private final AdminReportApplicationService service = new AdminReportApplicationService(repository);

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
    }

    @Test
    void storeScopeDefaultsNullStoreToCurrentStore() {
        TenantContextHolder.set(new TenantContext(100L, 100L, 102L, 1L, 1,
                List.of("report.view"), "STORE", "HOTEL", "Asia/Shanghai"));
        when(repository.selectOperations(eq(100L), eq(102L), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.operations(null, "2026-10-01", "2026-10-02", "DAY");

        verify(repository).selectOperations(eq(100L), eq(102L), any(), any(), anyInt());
    }

    @Test
    void storeScopeRejectsAnotherStore() {
        TenantContextHolder.set(new TenantContext(100L, 100L, 102L, 1L, 1,
                List.of("report.view"), "STORE", "HOTEL", "Asia/Shanghai"));

        ApiException error = assertThrows(ApiException.class,
                () -> service.operations(100L, "2026-10-01", "2026-10-02", "DAY"));

        assertEquals("REPORT_STORE_SCOPE_MISMATCH", error.getCode());
    }
}
