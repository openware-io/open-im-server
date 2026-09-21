package com.gvchat.common.audit.api.dto;

import com.gvchat.common.audit.domain.model.AuditLog;
import java.time.LocalDateTime;

/**
 * 审计记录查询返回体（前端按此字段名对接；字段与 {@code iam_audit_log} 一一对应，另加 {@code tenantName}）。
 *
 * <p><b>{@code detailJson} 是「已脱敏的 JSON 文本」（String），不是 databind 节点对象</b>：写入时已清洗，
 * 不会包含密码/token/密钥/完整手机号；读取时原样输出，前端 {@code JSON.parse} 后即为业务对象。
 *
 * <p>为什么这里必须是 String 而不能声明 {@code com.fasterxml.jackson.databind.JsonNode}（真机验收
 * 实测缺陷）：本工程是 Spring Boot 4 / Spring Framework 7，HTTP 消息转换器是 Jackson 3 的
 * {@code JacksonJsonHttpMessageConverter}（{@code tools.jackson.*}）。Jackson 2 的 {@code JsonNode}
 * 在 Jackson 3 眼里只是一个**普通 POJO**，会走 BeanSerializer，于是响应体里 {@code detailJson} 输出成
 * {@code {"array":false,...,"nodeType":"OBJECT","object":true,"pojo":false,...}} 这种元数据，
 * 真实业务内容（如 {@code {"orderId":76,"status":"ACTIVE"}}）整段丢失。
 * 同类规避在本仓已有先例：{@code TenantIamDomainClient}、{@code ReservationDomainClient} 的类注释都
 * 明确「避免 Spring Boot 4 消息转换器对 JsonNode 的序列化/反序列化问题」，统一按 String 取回再自己解析。
 */
public record AuditLogView(
        Long id,
        Long tenantId,
        String tenantName,
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
        String detailJson,
        LocalDateTime occurredAt,
        LocalDateTime createdAt) {

    /** 组装返回体；{@code tenantName} 由调用方按 tenant_id 批量补全（缺失时为 null）。 */
    public static AuditLogView of(AuditLog log, String tenantName, String detailJson) {
        return new AuditLogView(log.getId(), log.getTenantId(), tenantName, log.getOrganizationId(),
                log.getStoreId(), log.getOperatorId(), log.getOperatorName(), log.getOperatorAccount(),
                log.getOperatorType(), log.getAction(), log.getActionLabel(), log.getResourceType(),
                log.getResourceId(), log.getResourceName(), log.getResult(), log.getErrorCode(), log.getIp(),
                log.getUserAgent(), log.getRequestId(), log.getTraceId(), log.getSourceService(), detailJson,
                log.getOccurredAt(), log.getCreatedAt());
    }
}
