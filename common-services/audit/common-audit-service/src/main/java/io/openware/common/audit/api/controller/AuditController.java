package io.openware.common.audit.api.controller;

import io.openware.common.audit.api.dto.AuditLogView;
import io.openware.common.audit.api.dto.AuditPageView;
import io.openware.common.audit.api.dto.AuditQueryRequest;
import io.openware.common.audit.application.service.AuditQueryApplicationService;
import io.openware.common.audit.application.support.AuditTimeParser;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.time.TimeRangeParams;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计查询入口（网关 {@code /api/v1/admin/audits/**} → 本服务，controller 路径不带 /api）。
 *
 * <p>权限分层在应用服务内强制生效：
 * <ul>
 *   <li>缺少 {@code audit.view} → 403 PERMISSION_DENIED；</li>
 *   <li>平台视角（签名上下文 scopeType=PLATFORM 或无租户约束）→ 可看全部租户，可带 {@code tenantId}；</li>
 *   <li>租户视角 → 强制只返回本租户，传其它 {@code tenantId} → 403 AUDIT_TENANT_FORBIDDEN。</li>
 * </ul>
 * 参数口径见 {@link AuditQueryRequest}。
 */
@RestController
public class AuditController {

    private final AuditQueryApplicationService queryService;

    public AuditController(AuditQueryApplicationService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/admin/audits")
    public AuditPageView list(
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long organizationId,
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) String operatorKeyword,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actionPrefix,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String operatorType,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String fromAt,
            @RequestParam(required = false) String toAt,
            @RequestParam(required = false) String occurredFrom,
            @RequestParam(required = false) String occurredTo,
            @RequestParam(required = false) String order,
            @RequestParam(required = false) Boolean skipCount) {
        LocalDateTime fromBound = resolveFrom(from, fromAt, occurredFrom);
        LocalDateTime toBound = resolveTo(to, toAt, occurredTo);
        if (fromBound != null && toBound != null && !fromBound.isBefore(toBound)) {
            throw new ApiException(400, TimeRangeParams.CODE_TIME_RANGE_INVALID,
                    TimeRangeParams.MESSAGE_TIME_RANGE_INVALID);
        }
        AuditQueryRequest request = new AuditQueryRequest(
                positiveInt(page, "page", 1),
                pageSize(pageSize),
                tenantId,
                organizationId,
                storeId,
                operatorId,
                operatorKeyword,
                action,
                actionPrefix,
                resourceType,
                resourceId,
                result,
                operatorType,
                requestId,
                traceId,
                fromBound,
                toBound,
                // 默认按时间倒序（最新在前）；order=asc 才正序，与前端筛选默认值一致。
                "asc".equalsIgnoreCase(order == null ? "" : order.trim()),
                Boolean.TRUE.equals(skipCount));
        return queryService.list(request);
    }

    /**
     * 起始端解析：统一参数 {@code from} 优先，其次既有 {@code fromAt}，最后别名 {@code occurredFrom}。
     *
     * <p>统一参数走 {@link TimeRangeParams#parseFrom}（日期形态 → 当天 {@code 00:00:00.000}，
     * 非法格式 → 400 {@code TIME_RANGE_INVALID}）；两个既有参数保持原实现
     * {@link AuditTimeParser#parseDate} 的宽容口径（支持 ISO-8601 与 epoch），**不改既有行为**。
     */
    private static LocalDateTime resolveFrom(String unified, String fromAt, String occurredFrom) {
        if (present(unified)) {
            return TimeRangeParams.parseFrom(unified);
        }
        return AuditTimeParser.parseDate(present(fromAt) ? fromAt : occurredFrom, "fromAt");
    }

    /**
     * 结束端解析：统一参数 {@code to} 优先，其次既有 {@code toAt}，最后别名 {@code occurredTo}。
     *
     * <p><b>为什么用 {@link TimeRangeParams#parseToExclusive} 而不是 {@code parseTo}</b>：
     * 本模块仓储的时间条件是**左闭右开**（{@code occurred_at >= from AND occurred_at < to}，
     * 见 {@code AuditLogRepositoryImpl#applyTimeRange}）。要让「查到 2026-09-30 为止」整日命中，
     * 排他上界必须取**次日 00:00:00**；直接把闭区间上界 23:59:59.999 传下去会漏掉当天最后一毫秒。
     * 既有 {@code toAt}/{@code occurredTo} 仍走原来的 {@link AuditTimeParser#parseDate}，行为不变。
     */
    private static LocalDateTime resolveTo(String unified, String toAt, String occurredTo) {
        if (present(unified)) {
            return TimeRangeParams.parseToExclusive(unified);
        }
        return AuditTimeParser.parseDate(present(toAt) ? toAt : occurredTo, "toAt");
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    @GetMapping("/admin/audits/{id}")
    public AuditLogView detail(@PathVariable Long id) {
        return queryService.detail(id);
    }

    /** 动作码字典（已接入的动作与中文标签），前端筛选下拉直接使用。 */
    @GetMapping("/admin/audits/actions")
    public Map<String, Object> actions() {
        List<Map<String, String>> items = queryService.actions();
        return Map.of("items", items, "total", items.size());
    }

    private static int positiveInt(String raw, String field, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1) {
                throw new ApiException(400, "INVALID_ARGUMENT", field + " 必须大于 0");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new ApiException(400, "INVALID_ARGUMENT", field + " 必须是整数: " + raw);
        }
    }

    private static int pageSize(String raw) {
        int value = positiveInt(raw, "pageSize", 20);
        if (value > AuditQueryRequest.MAX_PAGE_SIZE) {
            throw new ApiException(400, "INVALID_ARGUMENT",
                    "pageSize 最大 " + AuditQueryRequest.MAX_PAGE_SIZE + "，实际 " + value);
        }
        return value;
    }
}
