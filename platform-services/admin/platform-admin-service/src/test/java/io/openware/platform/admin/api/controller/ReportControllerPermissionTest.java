package io.openware.platform.admin.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.admin.application.AdminReportApplicationService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 报表接口不是“登录即可读”：必须持有 report.view。 */
class ReportControllerPermissionTest {

    private final ReportController controller = new ReportController(mock(AdminReportApplicationService.class));

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    void rejectsContextWithoutReportPermission() {
        TenantContextHolder.set(new TenantContext(100L, 100L, 102L, 1L, 1,
                List.of("order.view"), "STORE", "HOTEL", "Asia/Shanghai"));

        ApiException error = assertThrows(ApiException.class, controller::requireReportPermission);
        assertEquals("PERMISSION_DENIED", error.getCode());
    }

    @Test
    void acceptsContextWithReportPermission() {
        TenantContextHolder.set(new TenantContext(100L, 100L, 102L, 1L, 1,
                List.of("report.view"), "STORE", "HOTEL", "Asia/Shanghai"));

        assertDoesNotThrow(controller::requireReportPermission);
    }
}
