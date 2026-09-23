package io.openware.common.audit.application.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.audit.application.support.AuditTimeParser;
import io.openware.common.audit.domain.model.AuditLog;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditActions;
import io.openware.infrastructure.audit.AuditDetailSanitizer;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 审计上报命令：内部接口 {@code POST /internal/audit/records} 的入参契约与校验。
 *
 * <p>校验口径（非法一律 400 INVALID_ARGUMENT，绝不 500）：
 * <ul>
 *   <li>{@code action} 必填，稳定码形态 {@code <模块>.<动作>}（如 {@code order.settle}），最长 128；</li>
 *   <li>{@code result}/{@code operatorType} 是枚举，非法值 400；</li>
 *   <li>各文本字段有长度上限，超长 400（不静默截断，避免审计内容被悄悄改小）；</li>
 *   <li>{@code detailJson} 统一脱敏并保证是合法 JSON，无法解析的按纯文本包装。</li>
 * </ul>
 */
public record AuditRecordCommand(
        Long tenantId,
        Long organizationId,
        Long storeId,
        Long operatorId,
        String operatorName,
        String operatorAccount,
        String operatorType,
        String action,
        String actionLabel,
        String resourceType,
        String resourceId,
        String resourceName,
        String result,
        String errorCode,
        String ip,
        String userAgent,
        String requestId,
        String traceId,
        String sourceService,
        String idempotencyKey,
        String detailJson,
        LocalDateTime occurredAt) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern ACTION_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*$");
    private static final Set<String> RESULTS = Set.of(AuditLog.RESULT_SUCCESS, AuditLog.RESULT_FAILURE);
    private static final Set<String> OPERATOR_TYPES =
            Set.of(AuditLog.OPERATOR_TYPE_PLATFORM, AuditLog.OPERATOR_TYPE_TENANT);

    /** 从上报报文体解析并校验；参数缺失/类型错误/超长/枚举非法都抛 400。 */
    public static AuditRecordCommand from(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw invalid("请求体不能为空");
        }
        String action = requireAction(body.get("action"));
        String result = enumText(body.get("result"), RESULTS, AuditLog.RESULT_SUCCESS, "result");
        String operatorType = enumText(body.get("operatorType"), OPERATOR_TYPES,
                AuditLog.OPERATOR_TYPE_TENANT, "operatorType");
        String actionLabel = text(body.get("actionLabel"), "actionLabel", 128);
        return new AuditRecordCommand(
                nonNegative(body.get("tenantId"), "tenantId", 0L),
                nonNegative(body.get("organizationId"), "organizationId", null),
                nonNegative(body.get("storeId"), "storeId", null),
                nonNegative(body.get("operatorId"), "operatorId", null),
                text(body.get("operatorName"), "operatorName", 128),
                text(body.get("operatorAccount"), "operatorAccount", 128),
                operatorType,
                action,
                actionLabel == null ? AuditActions.labelOf(action) : actionLabel,
                text(body.get("resourceType"), "resourceType", 64),
                text(body.get("resourceId"), "resourceId", 128),
                text(body.get("resourceName"), "resourceName", 255),
                result,
                text(body.get("errorCode"), "errorCode", 64),
                text(body.get("ip"), "ip", 45),
                text(body.get("userAgent"), "userAgent", 512),
                text(body.get("requestId"), "requestId", 64),
                text(body.get("traceId"), "traceId", 64),
                text(body.get("sourceService"), "sourceService", 64),
                text(body.get("idempotencyKey"), "idempotencyKey", 128),
                detail(body.get("detailJson")),
                occurredAt(body.get("occurredAt")));
    }

    /** 命令 → 领域对象（不含幂等键与创建时间，由应用服务补齐）。 */
    public AuditLog toDomain() {
        AuditLog log = new AuditLog();
        log.setTenantId(tenantId);
        log.setOrganizationId(organizationId);
        log.setStoreId(storeId);
        log.setOperatorId(operatorId == null ? 0L : operatorId);
        log.setOperatorName(operatorName);
        log.setOperatorAccount(operatorAccount);
        log.setOperatorType(operatorType);
        log.setAction(action);
        log.setActionLabel(actionLabel);
        log.setResourceType(resourceType);
        log.setResourceId(resourceId);
        log.setResourceName(resourceName);
        log.setResult(result);
        log.setErrorCode(errorCode);
        log.setIp(ip);
        log.setUserAgent(userAgent);
        log.setRequestId(requestId);
        log.setTraceId(traceId);
        log.setSourceService(sourceService);
        log.setDetailJson(detailJson);
        log.setOccurredAt(occurredAt);
        return log;
    }

    private static String requireAction(Object raw) {
        String action = text(raw, "action", 128);
        if (action == null || action.isBlank()) {
            throw invalid("action 不能为空");
        }
        if (!ACTION_PATTERN.matcher(action).matches()) {
            throw invalid("action 必须是形如 order.settle 的稳定码: " + action);
        }
        return action;
    }

    private static String enumText(Object raw, Set<String> allowed, String defaultValue, String field) {
        if (raw == null || (raw instanceof String value && value.isBlank())) {
            return defaultValue;
        }
        String value = String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw invalid(field + " 只允许 " + allowed + "，实际: " + raw);
        }
        return value;
    }

    private static String text(Object raw, String field, int maxLength) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Map || raw instanceof Iterable) {
            throw invalid(field + " 必须是字符串");
        }
        String value = String.valueOf(raw);
        if (value.length() > maxLength) {
            throw invalid(field + " 超过最大长度 " + maxLength + ": " + value.length());
        }
        return value;
    }

    private static Long nonNegative(Object raw, String field, Long defaultValue) {
        if (raw == null || (raw instanceof String value && value.isBlank())) {
            return defaultValue;
        }
        long value;
        if (raw instanceof Number number) {
            value = number.longValue();
        } else {
            try {
                value = Long.parseLong(String.valueOf(raw).trim());
            } catch (NumberFormatException exception) {
                throw invalid(field + " 必须是整数: " + raw);
            }
        }
        if (value < 0) {
            throw invalid(field + " 不能为负数: " + value);
        }
        return value;
    }

    /** 详情脱敏：对象/数组先序列化，再统一清洗为合法 JSON；超过上限折叠为 truncated 摘要。 */
    private static String detail(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof String value) {
            return AuditDetailSanitizer.sanitize(value);
        }
        try {
            return AuditDetailSanitizer.sanitize(OBJECT_MAPPER.writeValueAsString(raw));
        } catch (Exception exception) {
            return "{\"detailUnserializable\":true}";
        }
    }

    private static LocalDateTime occurredAt(Object raw) {
        return AuditTimeParser.parse(raw == null ? null : String.valueOf(raw), "occurredAt");
    }

    private static ApiException invalid(String message) {
        return new ApiException(400, "INVALID_ARGUMENT", message);
    }
}
