package io.openware.common.payment.domain.method.model;

/**
 * 租户支付方式授权（领域模型）。
 *
 * <p>两个开关语义不同，**不可合并**：
 * <ul>
 *   <li>{@code granted}——**总部授权**：该支付方式对这个租户是否被允许；</li>
 *   <li>{@code userEnabled}——**用户偏好**：在该租户被授权的前提下，用户自己是否启用。</li>
 * </ul>
 * 审计字段（createdBy、updatedBy、createdAt、updatedAt）不在此承载：由持久化适配器填充。
 *
 * @param id          配置标识
 * @param tenantId    租户
 * @param method      支付方式：CASH / WALLET / POINT / ALIPAY 等
 * @param granted     总部是否授权
 * @param userEnabled 用户是否启用
 * @param status      状态
 */
public record TenantPaymentMethod(
    Long id,
    Long tenantId,
    String method,
    Boolean granted,
    Boolean userEnabled,
    String status) {

  /** 配置生效中。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** 是否处于可用状态（已授权且已启用且生效中）——对客可用性的唯一判据。 */
  public boolean usable() {
    return STATUS_ACTIVE.equals(status) && Boolean.TRUE.equals(granted)
        && Boolean.TRUE.equals(userEnabled);
  }

  /** 是否被总部授权（不含用户偏好）。 */
  public boolean authorized() {
    return Boolean.TRUE.equals(granted);
  }

  /**
   * 变更开关。
   *
   * <p><b>用户开关只在显式给出时改变</b>：总部调整授权时不应顺带改写用户偏好——
   * 那会让「重新授权」变成「替用户启用」。
   *
   * @param newGranted     新的总部授权值
   * @param newUserEnabled 新的用户开关；{@code null} 表示本次不改动
   */
  public TenantPaymentMethod withSwitches(boolean newGranted, Boolean newUserEnabled) {
    return new TenantPaymentMethod(id, tenantId, method, newGranted,
        newUserEnabled == null ? userEnabled : newUserEnabled, status);
  }

  /** 新增配置（供调用方构造草稿，状态固定为 ACTIVE）。 */
  public static TenantPaymentMethod create(Long tenantId, String method, boolean granted,
                                           Boolean userEnabled) {
    return new TenantPaymentMethod(null, tenantId, method, granted, userEnabled, STATUS_ACTIVE);
  }
}
