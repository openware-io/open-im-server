package io.openware.platform.tenant.domain.pricing.model;

import java.math.BigDecimal;

/**
 * 计价方案（领域模型）。
 *
 * <p>审计字段（createdBy、updatedBy、createdAt、updatedAt）不在此承载：由持久化适配器填充，
 * 响应需要的值经 {@code Persisted.audit()} 读取。
 * {@code version} 保留：乐观锁并发语义，每次写入自增。
 *
 * <p><b>作用域由两个字段共同表达</b>（与 {@code uk_tnt_pricing_plan_scope_type} 一致）：
 * <ul>
 *   <li>{@code storeId == 0} → 租户级；{@code storeId > 0} → 门店级覆盖；</li>
 *   <li>{@code businessType} 为**空串**（不是 null，DDL 是 NOT NULL DEFAULT ''）→ 不限业态；</li>
 *   <li>非空 {@code businessType} → 限定该业态。</li>
 * </ul>
 * 因此"租户默认"是 {@code storeId == 0 && businessType 为空}，
 * "业态默认"是 {@code storeId == 0 && businessType 非空}。
 */
public record PricingPlan(
    Long id,
    Long tenantId,
    Long storeId,
    String businessType,
    String resourceType,
    String billingUnit,
    Integer incrementMinutes,
    String roundingDirection,
    Long pricePerUnit,
    Integer defaultSessionMinutes,
    BigDecimal overtimeRate,
    String status,
    Integer version,
    String idempotencyKey) {

  /** 方案生效中。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** 方案已停用。 */
  public static final String STATUS_DISABLED = "DISABLED";

  /** 租户级（非门店级覆盖）的 store_id 取值。 */
  public static final long TENANT_LEVEL_STORE_ID = 0L;

  /** 不限业态时 business_type 的取值（DDL 为 NOT NULL DEFAULT ''，故空串而非 null）。 */
  public static final String ANY_BUSINESS_TYPE = "";

  /** 资源类型：包厢。 */
  public static final String RESOURCE_KTV_ROOM = "KTV_ROOM";

  /** 资源类型：服务人员。 */
  public static final String RESOURCE_KTV_SERVER = "KTV_SERVER";

  /** 方案是否生效中。 */
  public boolean active() {
    return STATUS_ACTIVE.equals(status);
  }

  /** 是否为门店级覆盖（而非租户级）。 */
  public boolean storeLevel() {
    return storeId != null && storeId != TENANT_LEVEL_STORE_ID;
  }

  /** 是否限定了具体业态（非空业务类型）。 */
  public boolean businessTypeSpecific() {
    return businessType != null && !businessType.isBlank();
  }

  /** 业态是否与给定（已归一大写的）编码一致。 */
  public boolean hasBusinessType(String normalizedBusinessType) {
    return businessType != null && businessType.equals(normalizedBusinessType);
  }

  /**
   * 在"某门店 + 某业态"下解析该方案的作用域优先级，数字越小越优先；不适用时返回 {@code -1}。
   *
   * <p>优先级（与 {@code effective} 的语义一致）：
   * <ol>
   *   <li>{@code 0} 门店级且业态匹配——最具体的覆盖；</li>
   *   <li>{@code 1} 租户级且业态匹配——该业态的默认；</li>
   *   <li>{@code 2} 租户级且不限业态——兜底默认；</li>
   *   <li>{@code -1} 不适用。</li>
   * </ol>
   *
   * <p>判定收在模型上而不是散在调用方的过滤/排序闭包里：该三元组是计费正确性的核心
   * （取错优先级会按错误单价计费），必须只有一处口径、且可单独验证。
   */
  public int scopePrecedence(long targetStoreId, String normalizedBusinessType) {
    if (storeLevel()) {
      return storeId == targetStoreId && hasBusinessType(normalizedBusinessType) ? 0 : -1;
    }
    if (hasBusinessType(normalizedBusinessType)) {
      return 1;
    }
    return businessTypeSpecific() ? -1 : 2;
  }

  /**
   * 乐观锁更新前的版本校验。
   *
   * <p>仅用于把"客户端提交的版本是否与库中一致"翻译成领域判定；真正的并发保护必须由端口在执行 CAS 的
   * 那条 UPDATE 里完成（见端口说明），本方法**不能**替代它。
   */
  public boolean versionMatches(Integer submittedVersion) {
    return submittedVersion == null || submittedVersion.equals(version);
  }
}
