package io.openware.im.user.api.controller;

import io.openware.im.user.api.dto.request.OauthTokenRequest;
import io.openware.im.user.api.dto.response.OauthTokenResponse;
import io.openware.im.user.api.dto.response.OauthUserInfoResponse;
import io.openware.im.user.application.openplatform.OpenPlatformApplicationService;
import io.openware.im.user.application.openplatform.command.AuthorizeCommand;
import io.openware.im.user.application.openplatform.command.TokenCommand;
import io.openware.im.user.application.openplatform.result.OauthTokenResult;
import io.openware.im.user.application.openplatform.result.UserInfoResult;
import io.openware.infrastructure.security.SecurityUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth 2.0 Provider 端点：授权码 + PKCE(S256)，两层授权（应用级 + 用户级）。
 * /oauth/authorize 依赖登录态（用户 JWT）自动授权，真实 SSO 会话/同意页留待后续。
 */
@RestController
@Tag(name = "OAuth 2.0")
@RequestMapping("/oauth")
@RequiredArgsConstructor
public class OauthController {
  private final OpenPlatformApplicationService openPlatformApplicationService;

  @GetMapping("/authorize")
  public ResponseEntity<Void> authorize(
      @RequestParam(value = "client_id", required = false) String clientId,
      @RequestParam(value = "appId", required = false) String legacyAppId,
      @RequestParam("response_type") String responseType,
      @RequestParam("redirect_uri") String redirectUri,
      @RequestParam(required = false) String scope,
      @RequestParam String state,
      @RequestParam("code_challenge") String codeChallenge,
      @RequestParam(value = "code_challenge_method", defaultValue = "S256") String codeChallengeMethod,
      @RequestParam String nonce,
       @AuthenticationPrincipal SecurityUser user) {
    if (!"code".equals(responseType)) {
      throw new io.openware.common.exception.ApiException(HttpStatus.BAD_REQUEST.value(), "unsupported_response_type");
    }
    String appId = clientId == null || clientId.isBlank() ? legacyAppId : clientId;
    Long userId = user == null ? null : user.getId();
    String requestId = openPlatformApplicationService.createAuthorizationRequest(
        new AuthorizeCommand(appId, redirectUri, scope, state, codeChallenge, codeChallengeMethod, userId, nonce));
    String consentUrl = "/oauth/consent?request_id=" + URLEncoder.encode(requestId, StandardCharsets.UTF_8);
    return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, consentUrl).build();
  }

  @PostMapping("/token")
  public OauthTokenResponse token(@RequestBody OauthTokenRequest request) {
    OauthTokenResult result = openPlatformApplicationService.exchangeToken(
        new TokenCommand(request.grantType(), request.code(), request.codeVerifier(),
            request.refreshToken(), request.appId(), request.appSecret(), request.redirectUri()));
    return new OauthTokenResponse(result.accessToken(), result.refreshToken(), result.expiresIn(),
        result.openId(), result.scope(), result.idToken());
  }

  @PostMapping(value = "/token", consumes = "application/x-www-form-urlencoded")
  public OauthTokenResponse tokenForm(
      @RequestParam(name = "grant_type") String grantType,
      @RequestParam(required = false) String code,
      @RequestParam(name = "code_verifier", required = false) String codeVerifier,
      @RequestParam(name = "refresh_token", required = false) String refreshToken,
      @RequestParam(name = "client_id", required = false) String clientId,
      @RequestParam(name = "redirect_uri", required = false) String redirectUri,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
    String[] basic = parseBasic(authorization);
    String appId = clientId == null || clientId.isBlank() ? basic[0] : clientId;
    String appSecret = basic[1];
    OauthTokenResult result = openPlatformApplicationService.exchangeToken(
        new TokenCommand(grantType, code, codeVerifier, refreshToken, appId, appSecret, redirectUri));
    return new OauthTokenResponse(result.accessToken(), result.refreshToken(), result.expiresIn(),
        result.openId(), result.scope(), result.idToken());
  }

  @GetMapping("/userinfo")
  public OauthUserInfoResponse userinfo(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
    UserInfoResult result = openPlatformApplicationService.getUserInfo(extractBearerToken(authorization));
    return new OauthUserInfoResponse(result.openId(), result.nickname(), result.avatar(), result.phone(),
        result.scope(), result.sub());
  }

  @PostMapping("/revoke")
  public ResponseEntity<Void> revoke(@RequestParam String token) {
    openPlatformApplicationService.revokeToken(token);
    return ResponseEntity.ok().build();
  }

  @PostMapping(value = "/introspect", consumes = "application/x-www-form-urlencoded")
  public java.util.Map<String, Object> introspect(@RequestParam String token) {
    var access = openPlatformApplicationService.findActiveAccessToken(token);
    if (access.isPresent()) {
      var value = access.get();
      return java.util.Map.of("active", true, "client_id", value.appId(), "sub", value.openId(),
          "scope", value.scope(), "exp", value.expiresAt() / 1000);
    }
    return java.util.Map.of("active", false);
  }

  @PostMapping("/user/revoke")
  public ResponseEntity<Void> revokeUser(@RequestParam String appId,
      @AuthenticationPrincipal SecurityUser user) {
    if (user == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    openPlatformApplicationService.revokeUserAuthorization(appId, user.getId());
    return ResponseEntity.ok().build();
  }

  /** 组装 302 重定向地址；回调可能为自定义 scheme（如 gvchat://），故手动拼接 Location 而非依赖 URI 解析。
   *  code/state 做 URL 编码，避免自定义 scheme 与特殊字符破坏跳转或引入开放重定向。 */
  private ResponseEntity<Void> redirect(String redirectUri, String code, String state) {
    StringBuilder location = new StringBuilder(redirectUri);
    location.append(redirectUri.contains("?") ? '&' : '?');
    location.append("code=").append(URLEncoder.encode(code, StandardCharsets.UTF_8));
    if (state != null && !state.isBlank()) {
      location.append("&state=").append(URLEncoder.encode(state, StandardCharsets.UTF_8));
    }
    return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location.toString()).build();
  }

  private String extractBearerToken(String authorization) {
    if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return null;
    }
    return authorization.substring(7).trim();
  }

  private String[] parseBasic(String authorization) {
    if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
      return new String[] {null, null};
    }
    try {
      String decoded = new String(java.util.Base64.getDecoder().decode(authorization.substring(6).trim()),
          StandardCharsets.UTF_8);
      int separator = decoded.indexOf(':');
      return separator < 0 ? new String[] {null, null}
          : new String[] {decoded.substring(0, separator), decoded.substring(separator + 1)};
    } catch (IllegalArgumentException ex) {
      return new String[] {null, null};
    }
  }
}
