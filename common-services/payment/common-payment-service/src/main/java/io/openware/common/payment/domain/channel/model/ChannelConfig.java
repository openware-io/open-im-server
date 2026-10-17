package io.openware.common.payment.domain.channel.model;

/**
 * 线上支付渠道配置（领域模型）。
 *
 * <p>审计字段（createdBy、updatedBy、createdAt、updatedAt）不在此承载：由持久化适配器填充，
 * 响应需要的值经 `Persisted.audit()` 读取。
 * {@code version} 保留：乐观锁并发语义。
 *
 * <p><b>作用域由 (storeId, businessType) 共同表达</b>：{@code storeId == null} 表示租户级，
 * 非空表示门店级覆盖；{@code businessType} 空白表示不限业态。解析优先级见
 * {@link io.openware.common.payment.domain.channel.service.ChannelConfigResolver}。
 *
 * <p>{@code enabled} 用 Boolean 而不是 int：DDL 里是 {@code enabled TINYINT}，但"是否开通"在业务上
 * 只有真假两态，把 0/1 暴露到领域会让调用方写出 {@code getEnabled() == 1} 这样的比较。
 *
 * @param id             配置标识
 * @param tenantId       租户
 * @param storeId        门店；{@code null} 表示租户级
 * @param businessType   业态；空串表示不限
 * @param channel        渠道：ALIPAY / WECHAT / STRIPE
 * @param enabled        是否已开通
 * @param merchantId     商户号
 * @param status         状态：ACTIVE / DISABLED
 * @param version        版本号（乐观锁）
 * @param idempotencyKey 写入幂等键
 */
public record ChannelConfig(
    Long id,
    Long tenantId,
    Long storeId,
    String businessType,
    String channel,
    Boolean enabled,
    String merchantId,
    String status,
    Integer version,
    String idempotencyKey) {

  /** 配置生效中。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** 不限业态时的 business_type 取值。 */
  public static final String ANY_BUSINESS_TYPE = "";

  /** 是否生效中。 */
  public boolean active() {
    return STATUS_ACTIVE.equals(status);
  }

  /** 是否已开通（未生效的配置视为未开通）。 */
  public boolean enabledForUse() {
    return active() && Boolean.TRUE.equals(enabled);
  }

  /** 是否为门店级覆盖（而非租户级）。 */
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
