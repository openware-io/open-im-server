package io.openware.platform.tenant.domain.configuration.model;

/**
 * 租户/门店级配置项（领域模型）。
 *
 * <p>审计字段（createdBy、updatedBy）不在此承载：由持久化适配器填充。
 * {@code version} 保留：它是乐观锁并发语义。
 *
 * <p><b>作用域由两个字段共同表达</b>：{@code storeId == 0} 表示**租户级**配置（如租户币种），
 * {@code storeId > 0} 表示门店级覆盖；{@code businessType} 可进一步限定业态
 * （空串表示不限业态，与 DDL 的 NOT NULL DEFAULT '' 一致）。
 *
 * @param id           配置标识
 * @param tenantId     租户
 * @param storeId      门店；0 表示租户级
 * @param businessType 业态；空串表示不限
 * @param configKey    配置键
 * @param configValue  配置值
 * @param status       状态：ACTIVE / DISABLED
 * @param version      版本号（乐观锁）
 */
public record TenantConfig(
    Long id,
    Long tenantId,
    Long storeId,
    String businessType,
    String configKey,
    String configValue,
    String status,
    Integer version) {

  /** 配置生效中。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** 租户级（不区分门店）的 store_id 取值。 */
  public static final long TENANT_LEVEL_STORE_ID = 0L;

  /** 不限业态时的 business_type 取值（DDL 为 NOT NULL DEFAULT ''）。 */
  public static final String ANY_BUSINESS_TYPE = "";

  /** 租户币种配置键（standards/16 §2 的单一来源）。 */
  public static final String KEY_CURRENCY = "currency";

  /** 是否为租户级配置。 */
  public boolean tenantLevel() {
    return storeId != null && storeId == TENANT_LEVEL_STORE_ID;
  }

  /** 新建租户级配置：状态 ACTIVE、版本 0，尚未落库。 */
  public static TenantConfig tenantLevel(String configKey, Long tenantId, String configValue) {
    return new TenantConfig(null, tenantId, TENANT_LEVEL_STORE_ID, ANY_BUSINESS_TYPE, configKey,
        configValue, STATUS_ACTIVE, 0);
  }

  /** 变更配置值（不可变聚合生成新实例）。 */
  public TenantConfig withValue(String newValue) {
    return new TenantConfig(id, tenantId, storeId, businessType, configKey, newValue, status, version);
  }
}
