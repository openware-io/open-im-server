package com.gvchat.im.user.application.openplatform.result;

import java.util.List;

/**
 * 授权同意页视图数据：应用信息 + 可勾选范围 + 请求/已授权范围 + 回传参数（供同意页渲染）。
 */
public record ConsentView(
    String requestId,
    String appId,
    String appName,
    String redirectUri,
    List<String> allScopes,
    List<String> requestedScopes,
    List<String> existingAuthorizedScopes,
    String state,
    String codeChallenge,
    String codeChallengeMethod) {

  public ConsentView(String appId, String appName, String redirectUri, List<String> allScopes,
      List<String> requestedScopes, List<String> existingAuthorizedScopes, String state,
      String codeChallenge, String codeChallengeMethod) {
    this(null, appId, appName, redirectUri, allScopes, requestedScopes, existingAuthorizedScopes,
        state, codeChallenge, codeChallengeMethod);
  }
}
