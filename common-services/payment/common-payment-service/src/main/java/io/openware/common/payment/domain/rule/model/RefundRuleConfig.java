package io.openware.common.payment.domain.rule.model;

import java.math.BigDecimal;

/**
 * 退款规则配置（领域模型）。
 *
 * <p>审计字段（createdBy、updatedBy）不在此承载：由持久化适配器填充。
 * {@code version} 保留：乐观锁并发语义。
 *
 * <p><b>作用域由 (storeId, businessType) 共同表达</b>：{@code storeId == null} 表示租户级，
 * 非空表示门店级；{@code businessType} 空串表示不限业态（DDL 为 NOT NULL DEFAULT ''）。
 *
 * <p><b>门店行不承载资金策略</b>：{@code offlineRefundEnabled} 只在租户/业态层有意义，
 * 门店行的该字段是统一安全默认值，解析时从上层继承——否则门店页面就能绕过资金策略。
 *
 * @param id                    配置标识
 * @param tenantId              租户
 * @param businessType          业态；空串表示不限
 * @param storeId               门店；{@code null} 表示租户级
 * @param approvalThreshold     人工审批阈值（金额）
 * @param offlineRefundEnabled  线下退款开关（仅租户/业态层有效）
 * @param version               版本号（乐观锁）
 * @param idempotencyKey        写入幂等键
 * @param status                状态：ACTIVE / DISABLED
 */
public record RefundRuleConfig(
    Long id,
    Long tenantId,
    String businessType,
    Long storeId,
    BigDecimal approvalThreshold,
    Boolean offlineRefundEnabled,
    Integer version,
    String idempotencyKey,
    String status) {

  /** 配置生效中。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** 不限业态时的 business_type 取值。 */
  public static final String ANY_BUSINESS_TYPE = "";

  /** 是否生效中。 */
  public boolean active() {
    return STATUS_ACTIVE.equals(status);
  }

  /** 是否为门店级（而非租户级）。 */
  public boolean storeLevel() {
    return storeId != null;
  }

  /** 是否限定了具体业态。 */
  public boolean businessTypeSpecific() {
    return businessType != null && !businessType.isBlank();
  }

  /** 业态是否与给定（已归一大写的）编码一致。 */
  public boolean hasBusinessType(String normalizedBusinessType) {
    return businessType != null && businessType.equals(normalizedBusinessType);
  }
}
