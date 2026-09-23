package io.openware.platform.admin.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.admin.domain.model.AdminRole;
import io.openware.platform.admin.infra.security.AdminContext;
import io.openware.platform.admin.infra.security.AdminContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * SaaS 后台 BFF 操作审计拦截器：把「写操作」真正落到统一审计表。
 *
 * <p>规则（SAAS_PLATFORM_02 §9.4、KTV_BUSINESS_03 §4）：
 * <ul>
 *   <li>只审计写操作（POST/PUT/PATCH/DELETE）与敏感读（导出/下载 GET）——读取类请求不入库，避免噪声淹没</li>
 *   <li>动作码按 {@link AuditActionResolver} 从路由推导（含图片上传、恢复/取消/开关等特殊路径）；</li>
 *   <li>成功与失败都要留痕：异常或 4xx/5xx 落 {@code result=FAILURE} 并带 error_code；</li>
 *   <li>操作人取平台会话（accountId/platformAccountId + 显示名），租户与门店取签名运营上下文；</li>
 *   <li>上报失败只 WARN 不阻塞后台业务，但**不静默**——审计缺口必须在日志里可见。</li>
 * </ul>
 *
 * <p>请求体不入审计 detail（BFF 不缓存请求体，避免内存放大与密码等敏感字段被原样带出）；
 * detail 记录 method/path/query/status/耗时/requestId，业务语义字段由各领域服务在关键写入点补充。
 */
@Slf4j
public class AuditLogInterceptor implements HandlerInterceptor {

    private static final String ADMIN_PREFIX = "/admin/";
    private static final String START_ATTRIBUTE = "audit.startedAt";
    private static final String REQUEST_ID_ATTRIBUTE = "audit.requestId";
    private static final String REQUEST_HEADER = "X-Request-Id";

    private final AuditClient auditClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditLogInterceptor(AuditClient auditClient) {
        this.auditClient = auditClient;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!shouldAudit(request)) {
            return true;
        }
        request.setAttribute(START_ATTRIBUTE, System.currentTimeMillis());
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId(request));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception exception) {
        Object startedAt = request.getAttribute(START_ATTRIBUTE);
        if (startedAt == null) {
            return;
        }
        try {
            AdminContext admin = AdminContextHolder.get();
            TenantContext context = TenantContextHolder.get();
            AuditActionResolver.Resolved resolved =
                    AuditActionResolver.resolve(request.getMethod(), request.getRequestURI());
            boolean failed = exception != null || response.getStatus() >= 400;
            AuditClient.AuditRecord record = AuditClient.AuditRecord.builder()
                    .tenantId(context == null ? null : context.tenantId())
                    .organizationId(context == null ? null : context.organizationId())
                    .storeId(context == null ? null : context.storeId())
                    .operatorId(operatorId(admin))
                    .operatorName(admin == null ? null : admin.displayName())
                    .operatorAccount(admin == null ? null : admin.username())
                    .operatorType(operatorType(admin))
                    .action(resolved.action())
                    .actionLabel(resolved.actionLabel())
                    .resourceType(resolved.resourceType())
                    .resourceId(resolved.resourceId())
                    .requestId((String) request.getAttribute(REQUEST_ID_ATTRIBUTE))
                    .result(failed ? AuditClient.AuditRecord.RESULT_FAILURE
                            : AuditClient.AuditRecord.RESULT_SUCCESS)
                    .errorCode(failed ? errorCode(exception, response.getStatus()) : null)
                    .detailJson(detail(request, response, startedAt))
                    .build();
            auditClient.recordAsync(record);
        } catch (RuntimeException failure) {
            log.warn("后台操作审计上报失败(不阻塞业务): method={}, path={}, cause={}",
                    request.getMethod(), request.getRequestURI(), failure.getMessage());
        }
    }

    /** 写操作一律审计；只有导出/下载这类敏感读例外，其余读取不入库。 */
    private static boolean shouldAudit(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith(ADMIN_PREFIX)) {
            return false;
        }
        return AuditActionResolver.isWrite(request.getMethod())
                || AuditActionResolver.isSensitiveRead(request.getMethod(), path);
    }

    /** 平台账号标识优先，取不到时退化为本地后台账号；都没有则交给 AuditClient 按上下文补全。 */
    private static Long operatorId(AdminContext admin) {
        if (admin == null) {
            return null;
        }
        return admin.platformAccountId() != null ? admin.platformAccountId() : admin.accountId();
    }

    /** 平台超管/平台运营 → PLATFORM，租户后台管理员 → TENANT。 */
    private static String operatorType(AdminContext admin) {
        if (admin == null) {
            return AuditClient.AuditRecord.OPERATOR_TYPE_TENANT;
        }
        return admin.role() == AdminRole.TENANT_ADMIN
                ? AuditClient.AuditRecord.OPERATOR_TYPE_TENANT
                : AuditClient.AuditRecord.OPERATOR_TYPE_PLATFORM;
    }

    /** 失败错误码：业务异常带稳定码，框架级失败退化为 HTTP 状态码（都能检索）。 */
    private static String errorCode(Exception exception, int status) {
        if (exception instanceof ApiException apiException && apiException.getCode() != null) {
            return apiException.getCode();
        }
        if (exception != null) {
            return exception.getClass().getSimpleName();
        }
        return "HTTP_" + status;
    }

    private String detail(HttpServletRequest request, HttpServletResponse response, Object startedAt) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("method", request.getMethod());
        detail.put("path", request.getRequestURI());
        detail.put("query", request.getQueryString());
        detail.put("status", response.getStatus());
        detail.put("durationMs", Math.max(0, System.currentTimeMillis() - (Long) startedAt));
        detail.put("requestId", request.getAttribute(REQUEST_ID_ATTRIBUTE));
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception serializationFailure) {
            return null;
        }
    }

    /** 请求 ID：优先复用上游（网关联路）传入的 X-Request-Id，保证跨服务可串起来。 */
    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader(REQUEST_HEADER);
        if (header != null && !header.isBlank()) {
            return header.length() > 64 ? header.substring(0, 64) : header;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
