package com.gvchat.common.audit.api.controller;

import com.gvchat.common.audit.api.dto.AuditBatchWriteResult;
import com.gvchat.common.audit.api.dto.AuditInternalSearchRequest;
import com.gvchat.common.audit.api.dto.AuditPageView;
import com.gvchat.common.audit.api.dto.AuditQueryRequest;
import com.gvchat.common.audit.api.dto.AuditWriteResult;
import com.gvchat.common.audit.application.service.AuditLogApplicationService;
import com.gvchat.common.audit.application.service.AuditQueryApplicationService;
import com.gvchat.common.audit.infra.security.InternalAuditAuthenticationFilter;
import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.time.TimeRangeParams;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部审计上报入口（不走网关，仅平台内部服务调用）。
 *
 * <p>鉴权：{@code /internal/**} 由 {@link InternalAuditAuthenticationFilter} 校验鉴权版本 2 的
 * 内部服务 HMAC 签名与来源白名单，未通过直接 401，控制器不做任何信任假设。
 *
 * <p>契约：
 * <ul>
 *   <li>{@code POST /internal/audit/records}：单条上报，返回 {@code {id, duplicated}}；</li>
 *   <li>{@code POST /internal/audit/records/batch}：批量上报 {@code {records:[...]}}，单批上限 200 条，
 *       返回 {@code {accepted, duplicated, items:[{id, duplicated}]}}；</li>
 *   <li>{@code POST /internal/audit/records/search}：内部查询（IM 后台审计日志页），
 *       返回与公开端点 {@code GET /admin/audits} 相同的分页结构。</li>
 * </ul>
 * 写入幂等由 {@code (tenant_id, idempotency_key)} 唯一键保证；字段非法一律 400（不 500）。
 */
@RestController
public class InternalAuditController {

    private final AuditLogApplicationService auditLogService;
    private final AuditQueryApplicationService queryService;

    public InternalAuditController(AuditLogApplicationService auditLogService,
                                   AuditQueryApplicationService queryService) {
        this.auditLogService = auditLogService;
        this.queryService = queryService;
    }

    @PostMapping("/internal/audit/records")
    public AuditWriteResult write(@RequestBody(required = false) Map<String, Object> body,
                                  HttpServletRequest request) {
        return auditLogService.write(body == null ? Map.<String, Object>of() : body, source(request));
    }

    @PostMapping("/internal/audit/records/batch")
    @SuppressWarnings("unchecked")
    public AuditBatchWriteResult writeBatch(@RequestBody(required = false) Map<String, Object> body,
                                            HttpServletRequest request) {
        Object records = body == null ? null : body.get("records");
        if (!(records instanceof List<?> list)) {
            throw new ApiException(400, "INVALID_ARGUMENT", "records 必须是数组");
        }
        return auditLogService.writeBatch((List<Map<String, Object>>) list, source(request));
    }

    /**
     * 内部审计查询（IM 后台「审计日志」页）。
     *
     * <p>鉴权同上报通道：{@code /internal/**} 由 {@link InternalAuditAuthenticationFilter} 校验内部服务
     * HMAC 签名与来源白名单（只放审计上报通道身份），控制器不做权限码判定；
     * 时间区间与页大小在这里按公开端点 {@code GET /admin/audits} 的**同一口径**解析/夹取，
     * 避免各调用方各写一套（日期形态 {@code from} 取当天零点、{@code to} 取次日零点排他上界）。
     */
    @PostMapping("/internal/audit/records/search")
    public AuditPageView search(@RequestBody(required = false) AuditInternalSearchRequest body) {
        AuditInternalSearchRequest request = body == null
                ? new AuditInternalSearchRequest(null, null, null, null, null, null, null, null, null, null, null, null)
                : body;
        int page = request.page() == null || request.page() < 1 ? 1 : request.page();
        int pageSize = request.pageSize() == null || request.pageSize() < 1 ? 20 : request.pageSize();
        if (pageSize > AuditQueryRequest.MAX_PAGE_SIZE) {
            throw new ApiException(400, "INVALID_ARGUMENT",
                    "pageSize 最大 " + AuditQueryRequest.MAX_PAGE_SIZE + "，实际 " + pageSize);
        }
        LocalDateTime from = TimeRangeParams.parseFrom(request.from());
        LocalDateTime to = TimeRangeParams.parseToExclusive(request.to());
        if (from != null && to != null && !from.isBefore(to)) {
            throw new ApiException(400, TimeRangeParams.CODE_TIME_RANGE_INVALID,
                    TimeRangeParams.MESSAGE_TIME_RANGE_INVALID);
        }
        return queryService.searchInternal(new AuditQueryRequest(page, pageSize, null, null, null, null,
                request.operatorKeyword(), request.action(), request.actionPrefix(), request.resourceType(),
                request.resourceId(), request.result(), null, null, null, from, to,
                Boolean.TRUE.equals(request.ascending()), Boolean.TRUE.equals(request.skipCount())));
    }

    private static String source(HttpServletRequest request) {
        Object source = request.getAttribute(InternalAuditAuthenticationFilter.SOURCE_ATTRIBUTE);
        return source == null ? null : String.valueOf(source);
    }
}
