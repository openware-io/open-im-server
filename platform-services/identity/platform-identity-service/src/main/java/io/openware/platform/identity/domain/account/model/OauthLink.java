package io.openware.platform.identity.domain.account.model;

/**
 * 第三方登录绑定（领域模型）：账号与某个外部身份提供方的关联。
 *
 * <p>审计字段由适配器填充，不在此承载（见 standards/10“聚合装配与持久化契约”）。
 *
 * @param id                绑定主键
 * @param accountId         所属账号
 * @param provider          提供方标识，例如 WECHAT / ALIPAY / IM
 * @param providerAccountId 提供方侧的用户标识
 * @param scope             授权范围快照
 * @param status            绑定状态
 */
public record OauthLink(
    Long id,
    Long accountId,
    String provider,
    String providerAccountId,
    String scope,
    String status) {

  /** 绑定处于可用状态。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** 新建绑定：初始为可用状态，尚未落库（{@code id} 为空）。 */
  public static OauthLink bind(Long accountId, String provider, String providerAccountId, String scope) {
    return new OauthLink(null, accountId, provider, providerAccountId, scope, STATUS_ACTIVE);
  }

  /** 绑定是否可用。不可用的绑定不能被任何账号复用。 */
  public boolean isActive() {
    return STATUS_ACTIVE.equals(status);
  }

  /**
   * 绑定是否归属给定账号。
   *
   * <p>冲突判定的两半刻意分开：调用方需要区分"绑定不可用"与"绑定属于别人"，因为两者都拒绝，
   * 但只有后者说明同一提供方账号已被另一账号占用。把二者并成一个方法会让"登录标识尚不存在"的正常
   * 路径被误判为冲突。
   */
  public boolean belongsTo(Long candidateAccountId) {
    return candidateAccountId != null && candidateAccountId.equals(accountId);
  }
}
