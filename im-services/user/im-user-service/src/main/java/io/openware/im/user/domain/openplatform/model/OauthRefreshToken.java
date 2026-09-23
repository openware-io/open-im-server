package io.openware.im.user.domain.openplatform.model;

/**
 * OAuth refresh_token 载荷：refresh grant 支持轮换，旧 token 作废并标记已用（供重放检测）。
 *
 * @param openId   对应用暴露的用户标识
 * @param appId    应用标识
 * @param userId   用户 ID
 * @param scope    授权范围（空格分隔）
 * @param familyId token 家族标识：首次授权生成、随每次轮换继承，重放时按家族撤销
 */
public record OauthRefreshToken(
    String openId,
    String appId,
    long userId,
    String scope,
    String familyId) {
}
