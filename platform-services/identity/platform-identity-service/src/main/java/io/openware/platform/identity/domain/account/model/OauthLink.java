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

  /**
   * 该绑定能否被给定账号复用。
   *
   * <p>绑定冲突判定集中在这里：绑定必须处于可用状态，且归属账号一致；否则说明同一提供方账号被
   * 另一账号占用，调用方应拒绝而不是覆盖。
   */
  public boolean isReusableBy(Long candidateAccountId) {
    return STATUS_ACTIVE.equals(status) && java.util.Objects.equals(candidateAccountId, accountId);
  }
}
