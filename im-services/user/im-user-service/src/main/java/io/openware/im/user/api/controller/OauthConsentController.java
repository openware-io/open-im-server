package io.openware.im.user.api.controller;

import io.openware.im.user.application.openplatform.OpenPlatformApplicationService;
import io.openware.im.user.application.openplatform.result.AuthorizeResult;
import io.openware.im.user.application.openplatform.result.ConsentView;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth 2.0 授权同意页（最小 UI）：
 * <ul>
 *   <li>GET /oauth/consent：渲染 scope 勾选 + 同意/拒绝按钮的 HTML 授权页；</li>
 *   <li>POST /oauth/consent/approve：同意，按勾选 scope 签发授权码并 302 跳回回调；</li>
 *   <li>POST /oauth/consent/deny：拒绝，302 跳回回调并携带 error=access_denied。</li>
 * </ul>
 *
 * <p>后端为纯 API 服务（无模板引擎），授权页以返回内联 HTML 的最小方式提供；
 * 真实实现可替换为独立前端授权页或引入模板引擎。</p>
 */
@RestController
@RequestMapping("/oauth/consent")
@RequiredArgsConstructor
public class OauthConsentController {
  private static final Map<String, String> SCOPE_DESCRIPTIONS = Map.of(
      "profile.basic", "读取你的基础资料（昵称、头像）",
      "profile.phone", "读取你的手机号（脱敏展示）");

  private final OpenPlatformApplicationService openPlatformApplicationService;

  @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> consentPage(
      @RequestParam("request_id") String requestId) {
    ConsentView view = openPlatformApplicationService.prepareConsent(requestId);
    return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(renderConsentPage(view));
  }

  @PostMapping("/approve")
  public ResponseEntity<Void> approve(
      @RequestParam("request_id") String requestId,
      @RequestParam(required = false) List<String> scope) {
    AuthorizeResult result = openPlatformApplicationService.approveAuthorization(requestId, scope);
    return redirect(result.redirectUri(), result.authCode(), result.state());
  }

  @PostMapping("/deny")
  public ResponseEntity<Void> deny(
      @RequestParam("request_id") String requestId) {
    var request = openPlatformApplicationService.rejectAuthorization(requestId);
    return redirectError(request.redirectUri(), "access_denied", request.state());
  }

  /** 渲染最小授权页：应用名 + scope 勾选（请求范围预选）+ 同意/拒绝。 */
  String renderConsentPage(ConsentView view) {
    StringBuilder html = new StringBuilder(512);
    html.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
        .append("<title>授权登录 - ").append(escapeHtml(view.appName())).append("</title>")
        .append("<style>body{font-family:sans-serif;max-width:480px;margin:48px auto;padding:0 16px;color:#222}")
        .append(".card{border:1px solid #ddd;border-radius:8px;padding:24px}h2{font-size:20px;margin:0 0 8px}")
        .append(".scope{display:block;margin:10px 0}button{padding:10px 18px;border:0;border-radius:6px;")
        .append("cursor:pointer;font-size:15px;margin-right:8px}.approve{background:#1668dc;color:#fff}")
        .append(".deny{background:#eee;color:#333}</style></head><body><div class=\"card\">")
        .append("<h2>").append(escapeHtml(view.appName())).append(" 请求访问你的账号</h2>")
        .append("<p>该应用需要以下权限，请确认后选择同意或拒绝：</p>")
        .append("<form method=\"post\" action=\"/oauth/consent/approve\">")
        .append(hidden("request_id", view.requestId()));
    for (String scopeName : view.allScopes()) {
      boolean checked = view.requestedScopes().contains(scopeName);
      html.append("<label class=\"scope\"><input type=\"checkbox\" name=\"scope\" value=\"")
          .append(escapeHtml(scopeName)).append('"').append(checked ? " checked" : "").append("> ")
          .append(escapeHtml(scopeName)).append(" — ")
          .append(escapeHtml(SCOPE_DESCRIPTIONS.getOrDefault(scopeName, ""))).append("</label>");
    }
    html.append("<div style=\"margin-top:20px\">")
        .append("<button class=\"approve\" type=\"submit\">同意授权</button>")
        .append("<button class=\"deny\" type=\"submit\" formaction=\"/oauth/consent/deny\">拒绝</button>")
        .append("</div></form></div></body></html>");
    return html.toString();
  }

  private static String hidden(String name, String value) {
    return "<input type=\"hidden\" name=\"" + name + "\" value=\"" + escapeHtml(value) + "\">";
  }

  private static String escapeHtml(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;");
  }

  /** 组装 302 重定向地址（授权成功，携带 code/state）。 */
  private ResponseEntity<Void> redirect(String redirectUri, String code, String state) {
    StringBuilder location = new StringBuilder(redirectUri);
    location.append(redirectUri.contains("?") ? '&' : '?');
    location.append("code=").append(URLEncoder.encode(code, StandardCharsets.UTF_8));
    if (state != null && !state.isBlank()) {
      location.append("&state=").append(URLEncoder.encode(state, StandardCharsets.UTF_8));
    }
    return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location.toString()).build();
  }

  /** 组装 302 重定向地址（拒绝/错误，携带 error/state）。 */
  private ResponseEntity<Void> redirectError(String redirectUri, String error, String state) {
    StringBuilder location = new StringBuilder(redirectUri);
    location.append(redirectUri.contains("?") ? '&' : '?');
    location.append("error=").append(URLEncoder.encode(error, StandardCharsets.UTF_8));
    if (state != null && !state.isBlank()) {
      location.append("&state=").append(URLEncoder.encode(state, StandardCharsets.UTF_8));
    }
    return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location.toString()).build();
  }
}
