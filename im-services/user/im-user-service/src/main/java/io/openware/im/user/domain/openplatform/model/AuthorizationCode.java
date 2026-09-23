package io.openware.im.user.domain.openplatform.model;

/**
 * 授权码（短效）：存于 Redis，记录 PKCE challenge、回调与授权范围，换取 token 时一次性消费。
 *
 * @param code          授权码随机值
 * @param appId         应用标识
 * @param userId        授权用户 ID
 * @param redirectUri   授权时登记的回调地址
 * @param codeChallenge PKCE S256 challenge
 * @param scope         已授权范围（空格分隔）
 * @param expiresAt     过期时间（epoch 毫秒）
 */
public record AuthorizationCode(
    String code,
    String appId,
    long userId,
    String redirectUri,
    String codeChallenge,
    String scope,
    long expiresAt,
    String nonce) {
  public AuthorizationCode(String code, String appId, long userId, String redirectUri,
      String codeChallenge, String scope, long expiresAt) {
    this(code, appId, userId, redirectUri, codeChallenge, scope, expiresAt, null);
  }
}
