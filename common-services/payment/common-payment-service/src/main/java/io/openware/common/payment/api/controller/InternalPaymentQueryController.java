package io.openware.common.payment.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.common.payment.application.CollectApplicationService;
import io.openware.common.payment.application.PaymentOverviewApplicationService;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import io.openware.infrastructure.security.InternalServiceAuthenticationFilter;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 内部只读接口（Gateway 不外暴露 /internal/**）：供 order 域账单展示「已收分项」。
 *
 * <p>内部鉴权统一由 SDK 的 v2 canonical HMAC filter 完成。
 */
@RestController
@RequestMapping("/internal/payment")
public class InternalPaymentQueryController {
    private static final String SOURCE_HEADER = "X-IM-Service-Source";
    private static final String TIMESTAMP_HEADER = "X-IM-Service-Timestamp";
    private static final String SIGNATURE_HEADER = "X-IM-Service-Signature";

    private final CollectApplicationService collectService;
    private final PaymentOverviewApplicationService overviewService;
    private final HttpServletRequest request;

    public InternalPaymentQueryController(CollectApplicationService collectService,
                                          PaymentOverviewApplicationService overviewService,
                                          HttpServletRequest request) {
        this.collectService = collectService;
        this.overviewService = overviewService;
        this.request = request;
    }

    /** 按订单汇总已收分项：现金 / A380币（储值）/ 积分。 */
    @GetMapping("/orders/{orderId}/collected")
    public CollectApplicationService.OrderCollected collected(@PathVariable Long orderId) {
        verifyInternalAuth();
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        return collectService.orderCollected(context.tenantId(), orderId);
    }

    /**
     * Payment 域自身的总部现金/线上收款汇总；金额按支付方式与币种分组，避免跨币种相加。
     * wallet/points 抵扣属于 customer 域，由 customer overview 提供，不在此处重复猜测。
     */
    @GetMapping("/overview")
    public PaymentOverviewResponse overview(@RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to,
                                             @RequestParam(required = false) List<Long> storeIds) {
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
        if (storeIds != null && storeIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new ApiException(400, "STORE_FILTER_INVALID", "门店筛选非法");
        }
        List<PaymentOverviewApplicationService.PaymentOverviewRow> rows = overviewService.query(
                context.tenantId(), storeIds, fromTime, toTime);
        return new PaymentOverviewResponse(rows == null ? List.of() : rows, java.time.Instant.now());
    }

    private static LocalDateTime parse(String value, boolean end) {
        if (value == null || value.isBlank()) return null;
        try {
            if (value.length() == 10) {
                LocalDate date = LocalDate.parse(value);
                return end ? date.atTime(23, 59, 59, 999_000_000) : date.atStartOfDay();
            }
            return LocalDateTime.parse(value);
        } catch (RuntimeException e) {
            throw new ApiException(400, "TIME_RANGE_INVALID", "时间范围非法");
        }
    }

    public record PaymentOverviewResponse(List<PaymentOverviewApplicationService.PaymentOverviewRow> rows,
                                          java.time.Instant updatedAt) {}

    private void verifyInternalAuth() {
        if (!Boolean.TRUE.equals(request.getAttribute(InternalServiceAuthenticationFilter.AUTHENTICATED_ATTRIBUTE))) {
            throw new ApiException(401, "INVALID_INTERNAL_SERVICE_AUTHENTICATION", "内部服务鉴权失败");
        }
    }
}
