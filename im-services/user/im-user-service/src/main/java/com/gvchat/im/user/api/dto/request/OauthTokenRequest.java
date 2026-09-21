package com.gvchat.im.user.api.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAlias;

/** OAuth token 请求（authorization_code / refresh_token + PKCE S256）：body 同时含 snake_case 与 camelCase 字段。 */
public record OauthTokenRequest(
    @JsonProperty("grant_type") String grantType,
    String code,
    @JsonProperty("code_verifier") String codeVerifier,
    @JsonProperty("refresh_token") String refreshToken,
    @JsonAlias({"client_id", "app_id"}) String appId,
    @JsonAlias({"client_secret", "app_secret"}) String appSecret,
    @JsonProperty("redirect_uri") String redirectUri) {
}
