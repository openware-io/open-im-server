package com.gvchat.im.user.domain.openplatform.model;

/**
 * OAuth access_token 载荷：存于 Redis，供 /oauth/userinfo 解析出 open_id 与用户信息。
 *
 * @param openId    对应用暴露的用户标识
 * @param appId     应用标识
 * @param userId    用户 ID
 * @param scope     授权范围（空格分隔）
 * @param expiresAt 过期时间（epoch 毫秒）
 * @param familyId  token 家族标识：同一次授权/轮换链派生的全部 token 共享，用于 refresh 重放时的族级撤销
 */
public record OauthAccessToken(
    String openId,
    String appId,
    long userId,
    String scope,
    long expiresAt,
    String familyId) {
}
