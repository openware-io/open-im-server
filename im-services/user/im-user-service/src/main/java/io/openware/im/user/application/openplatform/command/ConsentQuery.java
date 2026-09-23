package io.openware.im.user.application.openplatform.command;

/** 授权同意页查询：与 /oauth/authorize 入参对齐，仅多一个 userId（来自登录态 JWT）。 */
public record ConsentQuery(
    String appId,
    String redirectUri,
    String scope,
    String state,
    String codeChallenge,
    String codeChallengeMethod,
    Long userId) {
}
