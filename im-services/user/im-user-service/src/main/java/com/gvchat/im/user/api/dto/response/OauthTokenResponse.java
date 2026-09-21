package com.gvchat.im.user.api.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OauthTokenResponse(
    @JsonProperty("access_token") String accessToken,
    @JsonProperty("refresh_token") String refreshToken,
    @JsonProperty("expires_in") long expiresIn,
    @JsonProperty("open_id") String openId,
    String scope,
    @JsonProperty("id_token") String idToken,
    @JsonProperty("token_type") String tokenType) {
  /** 兼容旧调用：旧六参形式默认补 token_type=Bearer。 */
  public OauthTokenResponse(String accessToken, String refreshToken, long expiresIn,
      String openId, String scope, String idToken) {
    this(accessToken, refreshToken, expiresIn, openId, scope, idToken, "Bearer");
  }

  public OauthTokenResponse(String accessToken, String refreshToken, long expiresIn,
      String openId, String scope) {
    this(accessToken, refreshToken, expiresIn, openId, scope, null, "Bearer");
  }
}
