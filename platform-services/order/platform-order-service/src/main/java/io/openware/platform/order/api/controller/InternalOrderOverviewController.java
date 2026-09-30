package io.openware.platform.order.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.security.InternalServiceAuthenticationFilter;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import io.openware.platform.order.application.OrderOverviewApplicationService;
import io.openware.platform.order.domain.overview.OrderOverviewQuery;
import io.openware.platform.order.domain.overview.OrderOverviewRow;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Order 域总部只读汇总端口；仅供 Admin 通过内部 v2 签名调用。 */
@RestController
@RequestMapping("/internal/order/overview")
public class InternalOrderOverviewController {
    private static final int MAX_STORE_IDS = 100;

    private final OrderOverviewApplicationService overviewService;
    private final HttpServletRequest request;

    public InternalOrderOverviewController(OrderOverviewApplicationService overviewService,
                                           HttpServletRequest request) {
        this.overviewService = overviewService;
        this.request = request;
    }

    @GetMapping
    public OrderOverviewResponse overview(@RequestParam(required = false) List<Long> storeIds,
                                          @RequestParam(required = false) String businessType,
                                          @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to) {
        verifyInternalAuth();
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        List<Long> normalizedStoreIds = normalizeStoreIds(storeIds);
        String normalizedBusinessType = normalizeBusinessType(businessType);
        TimeRange range = TimeRangeParams.parse(from, to);
        OrderOverviewApplicationService.Summary summary = overviewService.summarize(
                new OrderOverviewQuery(context.tenantId(), normalizedStoreIds, normalizedBusinessType,
                        range.fromInclusive(), range.toInclusive()));
        List<OrderOverviewRowResponse> rows = summary.rows().stream().map(OrderOverviewRowResponse::of).toList();
        return new OrderOverviewResponse(rows, summary.updatedAt());
    }

    private void verifyInternalAuth() {
        if (!Boolean.TRUE.equals(request.getAttribute(InternalServiceAuthenticationFilter.AUTHENTICATED_ATTRIBUTE))) {
            throw new ApiException(401, "INVALID_INTERNAL_SERVICE_AUTHENTICATION", "内部服务鉴权失败");
        }
    }

    private static List<Long> normalizeStoreIds(List<Long> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) return List.of();
        Set<Long> unique = new LinkedHashSet<>();
        for (Long id : storeIds) {
            if (id == null || id <= 0) {
                throw new ApiException(400, "STORE_FILTER_INVALID", "门店筛选非法");
            }
            unique.add(id);
        }
        if (unique.size() > MAX_STORE_IDS) {
            throw new ApiException(400, "STORE_FILTER_TOO_LARGE", "门店筛选数量超过上限");
        }
        return new ArrayList<>(unique);
    }

    private static String normalizeBusinessType(String businessType) {
        if (businessType == null || businessType.isBlank()) return null;
        String normalized = businessType.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_]{1,32}")) {
            throw new ApiException(400, "BUSINESS_TYPE_FILTER_INVALID", "业态筛选非法");
        }
        return normalized;
    }

    public record OrderOverviewResponse(List<OrderOverviewRowResponse> rows, Instant updatedAt) {
        public OrderOverviewResponse {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    public record OrderOverviewRowResponse(Long storeId, String businessType, String currencyCode,
                                           long orderCount, java.math.BigDecimal revenueAmount,
                                           java.math.BigDecimal paidAmount) {
        static OrderOverviewRowResponse of(OrderOverviewRow row) {
            return new OrderOverviewRowResponse(row.storeId(), row.businessType(), row.currencyCode(),
                    row.orderCount(), row.revenueAmount(), row.paidAmount());
        }
    }
}
