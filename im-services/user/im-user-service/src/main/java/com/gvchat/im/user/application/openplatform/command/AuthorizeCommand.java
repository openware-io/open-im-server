package com.gvchat.im.user.application.openplatform.command;

public record AuthorizeCommand(
    String appId,
    String redirectUri,
    String scope,
    String state,
    String codeChallenge,
    String codeChallengeMethod,
    Long userId,
    String nonce) {
  public AuthorizeCommand(String appId, String redirectUri, String scope, String state,
      String codeChallenge, String codeChallengeMethod, Long userId) {
    this(appId, redirectUri, scope, state, codeChallenge, codeChallengeMethod, userId, null);
  }
}
