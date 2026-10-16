package io.openware.common.payment.application;

import io.openware.common.payment.domain.method.model.TenantPaymentMethod;

import java.time.LocalDateTime;

/**
 * 租户支付方式授权/开关响应 DTO（由领域模型构造）。
 *
 * <p>{@code granted}/{@code userEnabled} 保持 {@code Integer}（0/1）以维持既有对外契约；
 * 领域模型里是布尔值，转换只在这一处发生。
 *
 * <p>不暴露记账字段（createdBy、updatedBy）：它们是审计事实，由审计留痕承载
 * （standards/10 的 D2 规则）。
 */
public record TenantPaymentMethodDto(Long id, Long tenantId, String method, Integer granted, Integer userEnabled,
                                     String status, LocalDateTime createdAt, LocalDateTime updatedAt) {

    /** 由领域模型构造；入参为空时返回空。 */
    public static TenantPaymentMethodDto from(TenantPaymentMethod config) {
        if (config == null) {
            return null;
        }
        return new TenantPaymentMethodDto(config.id(), config.tenantId(), config.method(),
                config.granted() == null ? null : (Boolean.TRUE.equals(config.granted()) ? 1 : 0),
                config.userEnabled() == null ? null : (Boolean.TRUE.equals(config.userEnabled()) ? 1 : 0),
                config.status(), config.createdAt(), config.updatedAt());
    }
}
