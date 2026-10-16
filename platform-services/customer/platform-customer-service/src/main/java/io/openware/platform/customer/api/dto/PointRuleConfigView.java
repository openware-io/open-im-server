package io.openware.platform.customer.api.dto;

import io.openware.platform.customer.domain.point.model.PointRuleConfig;
import java.math.BigDecimal;

/**
 * 积分规则配置的对外视图。
 *
 * <p>字段与 Admin 侧 BFF 的 {@code io.openware.platform.admin.api.ktv.PointRuleConfig} 一一对应——
 * 那个 record 由本接口的 JSON 直接反序列化而来，少一个字段就会在 Admin 侧静默变成 null。
 *
 * <p>不返回 {@code tenantId}：租户由签名上下文决定，回传它等于允许调用方比对/伪造他租户标识；
 * 也不返回 createdBy/updatedBy/createdAt/updatedAt（standards/10 D2：记账字段不进 API 契约）。
 */
public record PointRuleConfigView(
    Long id,
    Long storeId,
    String businessType,
    BigDecimal earnRate,
    BigDecimal redeemRate,
    Integer expiryDays,
    Long redeemCapPoints,
    Integer version,
    String idempotencyKey) {

  public static PointRuleConfigView from(PointRuleConfig config) {
    if (config == null) {
      return null;
    }
    return new PointRuleConfigView(config.id(), config.storeId(), config.businessType(),
        config.earnRate(), config.redeemRate(), config.expiryDays(), config.redeemCapPoints(),
        config.version(), config.idempotencyKey());
  }
}
