package io.openware.platform.tenant.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 计价方案响应（API 契约）。
 *
 * <p>此前接口直接返回持久化对象 {@code PricingPlanPo}（或在其上包一层内部 record）。本类型固定对外形状，
 * 且按 standards/10 的 D2 规则**不暴露**记账字段（createdBy、updatedBy）——{@code created_at}/
 * {@code updated_at} 作为方案变更时序对调用方可读，故保留。
 */
public record PricingPlanView(
    Long id,
    Long tenantId,
    Long storeId,
    String businessType,
    String resourceType,
    String billingUnit,
    Integer incrementMinutes,
    String roundingDirection,
    Long pricePerUnit,
    Integer defaultSessionMinutes,
    BigDecimal overtimeRate,
    String status,
    Integer version,
    String idempotencyKey,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {
}
