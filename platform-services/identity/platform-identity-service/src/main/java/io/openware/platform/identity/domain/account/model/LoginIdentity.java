package io.openware.platform.identity.domain.account.model;

/**
 * 登录标识（领域模型）：账号下的一条可登录凭据，例如手机号／邮箱／用户名。
 *
 * <p>审计字段由适配器填充，不在此承载（见 standards/10“聚合装配与持久化契约”）。
 *
 * <p>{@code credential} 是登录凭据，属敏感字段：不得进入日志、不得作为 API 响应直接返回。
 * 传入本模型时必须是**已编码**形态——编码算法属基础设施细节，由应用层完成后传入，领域不感知。
 *
 * @param id              登录标识主键
 * @param accountId       所属账号
 * @param loginType       登录类型：PHONE / EMAIL / USERNAME / IM 等
 * @param loginIdentifier 登录标识（手机号／邮箱／用户名本身）
 * @param credential      登录凭据（已编码）；IM／第三方等无密码场景可为空
 * @param status          该登录标识的状态
 */
public record LoginIdentity(
    Long id,
    Long accountId,
    String loginType,
    String loginIdentifier,
    String credential,
    String status) {

  /** 登录标识处于可用状态。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /**
   * 为某账号建立登录标识：初始为可用状态，尚未落库（{@code id} 为空）。
   *
   * @param credential 已编码的登录凭据；无密码场景传 {@code null}
   */
  public static LoginIdentity forLogin(Long accountId, String loginType, String loginIdentifier,
      String credential) {
    return new LoginIdentity(null, accountId, loginType, loginIdentifier, credential, STATUS_ACTIVE);
  }

  /** 校验该登录标识可用：非 ACTIVE 即拒绝。 */
  public LoginIdentity ensureActive() {
    if (!STATUS_ACTIVE.equals(status)) {
      throw new IllegalStateException("Identity account is not active");
    }
    return this;
  }
}
