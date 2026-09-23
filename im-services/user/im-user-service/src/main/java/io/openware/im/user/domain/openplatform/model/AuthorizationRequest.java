package io.openware.im.user.domain.openplatform.model;

/**
 * OAuth 授权事务票据：由已登录的 IM 会话创建，供同意页继续授权流程。
 * 票据只包含短期授权上下文，不携带登录 JWT。
 */
public record AuthorizationRequest(
    String requestId,
    String appId,
    String redirectUri,
    String scope,
    String state,
    String codeChallenge,
    String codeChallengeMethod,
    long userId,
    long expiresAt,
    String nonce) {
  public AuthorizationRequest(String requestId, String appId, String redirectUri, String scope,
      String state, String codeChallenge, String codeChallengeMethod, long userId, long expiresAt) {
    this(requestId, appId, redirectUri, scope, state, codeChallenge, codeChallengeMethod, userId,
        expiresAt, null);
  }
}
