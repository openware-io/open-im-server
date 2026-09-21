package com.gvchat.common.payment.application;

import com.gvchat.common.payment.infra.persistence.po.RefundPo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 退款响应 DTO（含币种快照 {@code currencyCode}：退款退原币种，见 16_CURRENCY_CONVENTIONS §5/§6.3）。 */
public record RefundDto(Long id, Long tenantId, Long orderId, Long paymentTransactionId, String requestId,
                        BigDecimal requestedAmount, BigDecimal approvedAmount, String currencyCode,
                        String providerRefundNo, String status,
                        String reason, Long requestedBy, Long approvedBy, Long createdBy, LocalDateTime createdAt,
                        Long updatedBy, LocalDateTime updatedAt) {
    public static RefundDto from(RefundPo po) {
        return new RefundDto(po.getId(), po.getTenantId(), po.getOrderId(), po.getPaymentTransactionId(), po.getRequestId(),
                po.getRequestedAmount(), po.getApprovedAmount(), po.getCurrencyCode(), po.getProviderRefundNo(),
                po.getStatus(), po.getReason(),
                po.getRequestedBy(), po.getApprovedBy(), po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt());
    }
}
