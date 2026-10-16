package io.openware.common.payment.domain.intent.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付意图（领域模型）：一次收款的意图与状态机载体。
 *
 * <p>审计字段（createdBy、updatedBy）不在此承载：由持久化适配器填充。
 * {@code createdAt}/{@code updatedAt}/{@code expiresAt} 保留——它们是业务时间
 * （预占有效期、超时释放判定），不是记账信息。
 *
 * @param id                意图标识
 * @param tenantId          租户
 * @param storeId           门店
 * @param orderId           订单
 * @param merchantAccountId 商户账户
 * @param provider          渠道
 * @param paymentMethod     支付方式
 * @param amount            金额
 * @param currencyCode      币种快照
 * @param status            状态
 * @param idempotencyKey    写入幂等键
 * @param expiresAt         失效时刻
 */
public record PaymentIntent(
    Long id,
    Long tenantId,
    Long storeId,
    Long orderId,
    Long merchantAccountId,
    String provider,
    String paymentMethod,
    BigDecimal amount,
    String currencyCode,
    String status,
    String idempotencyKey,
    LocalDateTime expiresAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {

  /** 支付成功（收款汇总只统计此状态）。 */
  public static final String STATUS_SUCCEEDED = "SUCCEEDED";
}
