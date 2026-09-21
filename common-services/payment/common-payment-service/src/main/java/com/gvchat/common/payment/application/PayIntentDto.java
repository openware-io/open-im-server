package com.gvchat.common.payment.application;

import com.gvchat.common.payment.infra.persistence.po.PayIntentPo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 支付流水响应 DTO（{@code currencyCode} 为收款时的币种快照，禁止被后改的租户设置改写）。 */
public record PayIntentDto(Long id, Long tenantId, Long storeId, Long orderId, Long merchantAccountId,
                           String provider, String paymentMethod, BigDecimal amount, String currencyCode, String status,
                           String idempotencyKey, LocalDateTime expiresAt, Long createdBy, LocalDateTime createdAt,
                           Long updatedBy, LocalDateTime updatedAt) {
    public static PayIntentDto from(PayIntentPo po) {
        return new PayIntentDto(po.getId(), po.getTenantId(), po.getStoreId(), po.getOrderId(), po.getMerchantAccountId(),
                po.getProvider(), po.getPaymentMethod(), po.getAmount(), po.getCurrencyCode(), po.getStatus(),
                po.getIdempotencyKey(), po.getExpiresAt(), po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt());
    }
}
