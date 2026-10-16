package io.openware.common.payment.domain.refund.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款单（领域模型）。
 *
 * <p>审计字段（createdBy、updatedBy）不在此承载：由持久化适配器填充。
 * {@code requestedBy}/{@code approvedBy} 保留——它们是**业务角色**（谁申请、谁审批），
 * 不是记账信息，审批链的追溯依赖它们。
 *
 * <p>{@code currencyCode} 是**原交易的币种快照**：退款退原币种（standards/16 §5/§6.3），
 * 不允许按当前租户币种折算。
 *
 * @param id                   退款标识
 * @param tenantId             租户
 * @param storeId              门店
 * @param businessType         业态
 * @param orderId              订单
 * @param paymentTransactionId 原支付交易
 * @param requestId            请求标识
 * @param requestedAmount      申请退款金额
 * @param approvedAmount       批准退款金额
 * @param currencyCode         币种快照（与原交易一致）
 * @param providerRefundNo     渠道退款单号
 * @param status               状态
 * @param reason               原因
 * @param requestedBy          申请人
 * @param approvedBy           审批人
 * @param createdAt            创建时刻
 * @param updatedAt            更新时刻
 */
public record Refund(
    Long id,
    Long tenantId,
    Long storeId,
    String businessType,
    Long orderId,
    Long paymentTransactionId,
    String requestId,
    BigDecimal requestedAmount,
    BigDecimal approvedAmount,
    String currencyCode,
    String providerRefundNo,
    String status,
    String reason,
    Long requestedBy,
    Long approvedBy,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {
}
