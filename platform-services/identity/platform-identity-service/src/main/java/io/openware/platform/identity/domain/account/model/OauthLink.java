package io.openware.platform.identity.domain.account.model;

/**
 * 第三方登录绑定（领域模型）：账号与某个外部身份提供方的关联。
 *
 * <p>审计字段由适配器填充，不在此承载（见 standards/10“聚合装配与持久化契约”）。
 *
 * @param id                绑定主键
 * @param accountId         所属账号
 * @param provider          提供方标识，例如 WECHAT / ALIPAY
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
}
