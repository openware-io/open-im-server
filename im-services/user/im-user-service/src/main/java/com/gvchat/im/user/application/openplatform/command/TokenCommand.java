package com.gvchat.im.user.application.openplatform.command;

/** OAuth token 请求命令：authorization_code（code + PKCE）或 refresh_token（轮换）两种 grant。 */
public record TokenCommand(
    String grantType,
    String code,
    String codeVerifier,
    String refreshToken,
    String appId,
    String appSecret,
    String redirectUri) {
}
