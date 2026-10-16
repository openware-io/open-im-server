package io.openware.platform.customer.domain.point.model;

import java.math.BigDecimal;

/**
 * 积分规则配置（领域模型）。
 *
 * <p>审计字段（createdBy、updatedBy）不在此承载：由持久化适配器填充。
 * {@code version} 保留：乐观锁并发语义。
 *
 * <p><b>作用域由 (storeId, businessType) 共同表达</b>：{@code storeId == 0} 表示租户级，
 * 非空正数表示门店级；{@code businessType} 空串表示不限业态（DDL 为 NOT NULL DEFAULT ''）。
 * 生效顺序见 {@link io.openware.platform.customer.domain.point.service.PointRuleResolver}。
 *
 * @param id             配置标识
 * @param tenantId       租户
 * @param businessType   业态；空串表示不限
 * @param storeId        门店；0 表示租户级
 * @param earnRate       获取倍率（1 = 消费 1 元得 1 分）
 * @param redeemRate     抵扣倍率
 * @param expiryDays     积分有效期天数；0 表示不过期
 * @param redeemCapPoints 单笔积分抵扣上限；0 表示不限
 * @param version        版本号（乐观锁）
 * @param idempotencyKey 写入幂等键
 * @param status         状态：ACTIVE / DISABLED
 */
public record PointRuleConfig(
    Long id,
    Long tenantId,
    String businessType,
    Long storeId,
    BigDecimal earnRate,
    BigDecimal redeemRate,
    Integer expiryDays,
    Long redeemCapPoints,
    Integer version,
    String idempotencyKey,
    String status) {

  /** 配置生效中。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** 租户级（不分叉到门店）的 store_id 取值。 */
  public static final long TENANT_LEVEL_STORE_ID = 0L;

  /** 不限业态时的 business_type 取值（DDL 为 NOT NULL DEFAULT ''）。 */
  public static final String ANY_BUSINESS_TYPE = "";

  /** 倍率缺省值：1（不加成、不打折）。 */
  public static final BigDecimal DEFAULT_RATE = BigDecimal.ONE;

  /** 是否生效中。 */
  public boolean active() {
    return STATUS_ACTIVE.equals(status);
  }

  /** 是否为门店级（而非租户级）。 */
  public boolean storeLevel() {
    return storeId != null && storeId != TENANT_LEVEL_STORE_ID;
  }

  /** 是否限定了具体业态。 */
  public boolean businessTypeSpecific() {
    return businessType != null && !businessType.isBlank();
  }

  /** 业态是否与给定（已归一大写的）编码一致。 */
  public boolean hasBusinessType(String normalizedBusinessType) {
    return businessType != null && businessType.equals(normalizedBusinessType);
  }

  /** 有效期天数；为空视为不过期。 */
  public int expiryDaysOrZero() {
    return expiryDays == null ? 0 : expiryDays;
  }

  /** 抵扣上限；为空视为不限。 */
  public long redeemCapPointsOrZero() {
    return redeemCapPoints == null ? 0L : redeemCapPoints;
  }

  /** 版本号；为空视为 0。 */
  public int currentVersion() {
    return version == null ? 0 : version;
  }
}
