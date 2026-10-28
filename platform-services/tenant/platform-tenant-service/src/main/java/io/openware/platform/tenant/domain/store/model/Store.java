package io.openware.platform.tenant.domain.store.model;

/**
 * 门店（领域模型）。
 *
 * <p>审计字段（createdBy/At、updatedBy/At）不在此承载：它们是记账信息，由持久化适配器填充。
 *
 * <p>{@code businessType} 保持字符串而非枚举：取值来自 {@code tnt_business_type} **字典表**
 * （由 {@link io.openware.platform.tenant.domain.store.repository.StoreRepository#findActiveBusinessTypeCode}
 * 校验），属于可维护的配置数据，硬编码成枚举会把字典表变成编译期常量。
 *
 * @param id                  门店标识
 * @param tenantId            租户
 * @param organizationId      所属组织
 * @param code                门店编码（租户内唯一）
 * @param name                门店名
 * @param businessType        业态编码（取自业态字典）
 * @param countryCode         国家代码
 * @param regionCode          地区代码
 * @param timezone            时区
 * @param defaultCurrency     默认币种
 * @param locale              语言区域
 * @param taxProfileId        税档
 * @param businessDayCutoff   营业日切分点
 * @param status              状态：ACTIVE / SUSPENDED / CLOSED
 */
public record Store(
    Long id,
    Long tenantId,
    Long organizationId,
    String code,
    String name,
    String businessType,
    String countryCode,
    String regionCode,
    String timezone,
    String defaultCurrency,
    String locale,
    Long taxProfileId,
    String businessDayCutoff,
    String status,
    String logoUrl) {

  /** 门店处于可用状态。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** 是否处于可用状态。 */
  public boolean active() {
    return STATUS_ACTIVE.equals(status);
  }

  /** 业态是否与给定编码一致（大小写不敏感；入参应为已归一的大写）。 */
  public boolean hasBusinessType(String normalizedBusinessType) {
    return businessType != null && businessType.equalsIgnoreCase(normalizedBusinessType);
  }
}
