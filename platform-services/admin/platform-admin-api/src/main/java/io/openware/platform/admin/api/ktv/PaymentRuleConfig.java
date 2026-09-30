package io.openware.platform.admin.api.ktv;

import java.math.BigDecimal;

/** Payment 域退款/日结规则的 BFF DTO。 */
public record PaymentRuleConfig(Long storeId, String businessType, BigDecimal approvalThreshold,
                                Boolean offlineRefundEnabled, Integer closingMinute, Integer version,
                                String idempotencyKey) {}
