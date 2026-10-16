package io.openware.common.payment.application;

import io.openware.common.payment.domain.intent.model.PaymentIntent;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付流水响应 DTO（由领域模型构造；{@code currencyCode} 为收款时的币种快照，禁止被后改的租户设置改写）。
 *
 * <p>不暴露记账字段（createdBy、updatedBy）：它们是审计事实，由审计留痕承载
 * （standards/10 的 D2 规则）。
 */
public record PayIntentDto(Long id, Long tenantId, Long storeId, Long orderId, Long merchantAccountId,
                           String provider, String paymentMethod, BigDecimal amount, String currencyCode,
                           String status,
                           String idempotencyKey, LocalDateTime expiresAt, LocalDateTime createdAt,
                           LocalDateTime updatedAt) {

    /** 由领域模型构造；入参为空时返回空。 */
    public static PayIntentDto from(PaymentIntent intent) {
        if (intent == null) {
            return null;
        }
        return new PayIntentDto(intent.id(), intent.tenantId(), intent.storeId(), intent.orderId(),
                intent.merchantAccountId(), intent.provider(), intent.paymentMethod(), intent.amount(),
                intent.currencyCode(), intent.status(), intent.idempotencyKey(), intent.expiresAt(),
                intent.createdAt(), intent.updatedAt());
    }
}
