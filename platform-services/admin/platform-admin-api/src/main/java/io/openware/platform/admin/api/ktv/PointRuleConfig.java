package io.openware.platform.admin.api.ktv;

import java.math.BigDecimal;

/** Customer 域积分经营规则的 BFF DTO。 */
public record PointRuleConfig(Long id, Long storeId, String businessType, BigDecimal earnRate,
                              BigDecimal redeemRate, Integer expiryDays, Long redeemCapPoints,
                              Integer version, String idempotencyKey) {}
