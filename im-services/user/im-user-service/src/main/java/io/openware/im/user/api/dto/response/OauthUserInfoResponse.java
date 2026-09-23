package io.openware.im.user.api.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OauthUserInfoResponse(
    @JsonProperty("open_id") String openId,
    String nickname,
    String avatar,
    String phone,
    String scope,
    String sub) {
  public OauthUserInfoResponse(String openId, String nickname, String avatar, String phone, String scope) {
    this(openId, nickname, avatar, phone, scope, openId);
  }
}
