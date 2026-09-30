package io.openware.platform.customer.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.customer.application.CustomerOverviewApplicationService;
import java.time.Instant;
import java.time.LocalDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;
import io.openware.infrastructure.security.InternalServiceAuthenticationFilter;

/** Customer 域总部只读汇总端口；Admin 只能通过内部认证调用，不能直连本域数据库。 */
@RestController
@RequestMapping("/internal/customer/overview")
public class InternalCustomerOverviewController {
    private final CustomerOverviewApplicationService overviewService;
    private final HttpServletRequest request;

    public InternalCustomerOverviewController(CustomerOverviewApplicationService overviewService,
                                              HttpServletRequest request) {
        this.overviewService = overviewService;
        this.request = request;
    }

    @GetMapping
    public CustomerOverviewResponse overview(@RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to,
                                             @RequestParam(required = false) String storeIds) {
        verifyInternalAuth();
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        LocalDateTime fromTime = parse(from, false);
        LocalDateTime toTime = parse(to, true);
        if (fromTime != null && toTime != null && fromTime.isAfter(toTime)) {
            throw new ApiException(400, "TIME_RANGE_INVALID", "时间范围非法");
        }
        validateStoreIds(storeIds);
        CustomerOverviewApplicationService.Summary summary = overviewService.summarize(fromTime, toTime, storeIds);
        return new CustomerOverviewResponse(summary.memberCount(), summary.pointsBalance(), summary.walletBalance(),
                summary.pointsDelta(), summary.walletDelta(), summary.updatedAt());
    }

    private void verifyInternalAuth() {
        if (!Boolean.TRUE.equals(request.getAttribute(InternalServiceAuthenticationFilter.AUTHENTICATED_ATTRIBUTE))) {
            throw new ApiException(401, "INVALID_INTERNAL_SERVICE_AUTHENTICATION", "内部服务鉴权失败");
        }
    }

    private static LocalDateTime parse(String value, boolean end) {
        if (value == null || value.isBlank()) return null;
        try {
            if (value.length() == 10) {
                return java.time.LocalDate.parse(value).atTime(end ? 23 : 0, end ? 59 : 0,
                        end ? 59 : 0, end ? 999_000_000 : 0);
            }
            return LocalDateTime.parse(value);
        } catch (RuntimeException e) {
            throw new ApiException(400, "TIME_RANGE_INVALID", "时间范围非法");
        }
    }

    private static void validateStoreIds(String storeIds) {
        if (storeIds == null || storeIds.isBlank()) return;
        try {
            java.util.List<Long> ids = java.util.Arrays.stream(storeIds.split(","))
                    .map(String::trim).filter(s -> !s.isBlank()).map(Long::valueOf).toList();
            if (ids.isEmpty() || ids.stream().anyMatch(id -> id <= 0)) throw new NumberFormatException();
        } catch (RuntimeException e) {
            throw new ApiException(400, "STORE_FILTER_INVALID", "门店筛选非法");
        }
    }


    public record CustomerOverviewResponse(long memberCount, long pointsBalance, long walletBalance,
                                           long pointsDelta, long walletDelta, Instant updatedAt) {
    }
}
