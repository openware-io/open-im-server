package io.openware.common.payment.application;

import io.openware.common.payment.domain.channel.repository.Persisted;
import io.openware.common.payment.domain.refund.model.Refund;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款响应 DTO（由「聚合 + 记账快照」构造；含门店归因与币种快照；退款退原币种，见 16_CURRENCY_CONVENTIONS §5/§6.3）。
 *
 * <p>不暴露记账字段（createdBy、updatedBy）：它们是审计事实，由审计留痕承载
 * （standards/10 的 D2 规则）。{@code requestedBy}/{@code approvedBy} 保留——它们是**业务角色**
 * （谁申请、谁审批），审批链追溯依赖它们。{@code createdAt}/{@code updatedAt} 从审计快照取。
 */
public record RefundDto(Long id, Long tenantId, Long storeId, Long orderId, Long paymentTransactionId,
                        String requestId,
                        BigDecimal requestedAmount, BigDecimal approvedAmount, String currencyCode,
                        String providerRefundNo, String status,
                        String reason, Long requestedBy, Long approvedBy, LocalDateTime createdAt,
                        LocalDateTime updatedAt) {

    /** 由「聚合 + 记账快照」构造；入参为空时返回空。 */
    public static RefundDto from(Persisted<Refund> persisted) {
        if (persisted == null || persisted.aggregate() == null) {
            return null;
        }
        Refund refund = persisted.aggregate();
        return new RefundDto(refund.id(), refund.tenantId(), refund.storeId(), refund.orderId(),
                refund.paymentTransactionId(), refund.requestId(), refund.requestedAmount(),
                refund.approvedAmount(), refund.currencyCode(), refund.providerRefundNo(),
                refund.status(), refund.reason(), refund.requestedBy(), refund.approvedBy(),
                persisted.audit() == null ? null : persisted.audit().createdAt(),
                persisted.audit() == null ? null : persisted.audit().updatedAt());
    }
}
