package io.openware.common.payment.domain.transaction.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付交易（领域模型）。
 *
 * <p>审计字段（createdBy、updatedBy）不在此承载：由持久化适配器填充。
 *
 * <p>{@code currencyCode} 是**交易发生时的币种快照**，不是当前的租户币种：
 * 租户改币种后历史交易仍须按其发生时的币种统计，否则历史对账金额会随配置变动而变化。
 *
 * @param id                  交易标识
 * @param tenantId            租户
 * @param storeId             门店
 * @param businessType        业态
 * @param paymentIntentId     支付意图
 * @param provider            渠道
 * @param providerTransactionNo 渠道交易号
 * @param providerPayloadDigest 回调报文摘要（用于对账比对）
 * @param amount              交易金额
 * @param currencyCode        币种快照
 * @param exchangeRate        汇率
 * @param feeAmount           渠道手续费
 * @param status              状态：SUCCEEDED / FAILED / PENDING 等
 * @param occurredAt          交易发生时刻（对账区间按此列）
 * @param rawReference        原始引用
 */
public record PaymentTransaction(
    Long id,
    Long tenantId,
    Long storeId,
    String businessType,
    Long paymentIntentId,
    String provider,
    String providerTransactionNo,
    String providerPayloadDigest,
    BigDecimal amount,
    String currencyCode,
    BigDecimal exchangeRate,
    BigDecimal feeAmount,
    String status,
    LocalDateTime occurredAt,
    String rawReference) {

  /** 交易成功（对账只统计成功交易）。 */
  public static final String STATUS_SUCCEEDED = "SUCCEEDED";

  /** 手续费；为空视为零，避免调用方到处判空。 */
  public BigDecimal feeOrZero() {
    return feeAmount == null ? BigDecimal.ZERO : feeAmount;
  }

  /** 净额 = 金额 - 手续费。 */
  public BigDecimal netAmount() {
    return amount().subtract(feeOrZero());
  }
}
