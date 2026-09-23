package io.openware.im.user.application.openplatform.result;

public record OauthTokenResult(
    String accessToken,
    String refreshToken,
    long expiresIn,
    String openId,
    String scope,
    String idToken) {
  public OauthTokenResult(String accessToken, String refreshToken, long expiresIn,
      String openId, String scope) {
    this(accessToken, refreshToken, expiresIn, openId, scope, null);
  }
}
