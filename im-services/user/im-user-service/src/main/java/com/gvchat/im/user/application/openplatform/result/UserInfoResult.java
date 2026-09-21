package com.gvchat.im.user.application.openplatform.result;

public record UserInfoResult(
    String openId,
    String nickname,
    String avatar,
    String phone,
    String scope,
    String sub) {
  public UserInfoResult(String openId, String nickname, String avatar, String phone, String scope) {
    this(openId, nickname, avatar, phone, scope, null);
  }
}
