package io.openware.platform.tenant.api.dto;

import java.time.LocalDateTime;

/**
 * 租户响应（API 契约）。
 *
 * <p>此前接口直接返回持久化对象 {@code TenantPo}。本类型固定对外形状，且按 standards/10 的 D2 规则
 * **不暴露**记账字段（createdBy、updatedBy）。
 *
 * <p>{@code createdAt} 保留：它是业务事实（平台按创建时间筛选租户），不是记账信息。
 */
public record TenantView(
    Long id,
    String tenantCode,
    String name,
    String status,
    String defaultLocale,
    String defaultTimezone,
    LocalDateTime createdAt) {
}
