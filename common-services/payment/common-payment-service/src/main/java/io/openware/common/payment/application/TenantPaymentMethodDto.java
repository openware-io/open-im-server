package io.openware.common.payment.application;

import io.openware.common.payment.infra.persistence.po.TenantPaymentMethodPo;

import java.time.LocalDateTime;

/** 租户支付方式授权/开关响应 DTO。 */
public record TenantPaymentMethodDto(Long id, Long tenantId, String method, Integer granted, Integer userEnabled,
                                     String status, LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static TenantPaymentMethodDto from(TenantPaymentMethodPo po) {
        return new TenantPaymentMethodDto(po.getId(), po.getTenantId(), po.getMethod(), po.getGranted(), po.getUserEnabled(),
                po.getStatus(), po.getCreatedAt(), po.getUpdatedAt());
    }
}
