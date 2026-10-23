package io.openware.platform.identity.api.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.openware.platform.identity.application.OAuthImBindApplicationService;
import io.openware.platform.identity.application.OAuthImBindApplicationService.BindResult;
import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.platform.identity.infra.security.SaasAppRegistry;
import io.openware.platform.identity.infra.security.SaasUserSessionCookie;
import io.openware.platform.identity.infra.security.SaasUserSessionStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SaaS 身份 OAuth 绑定入口。
 * 网关 /api/v1/identity/** -> platform-identity-service(4100)，本控制器路径不带 /api。
 */
@RestController
@RequestMapping("/identity")
public class OAuthImBindController {
    private final OAuthImBindApplicationService bindService;
    private final SaasUserSessionStore sessionStore;
    private final SaasAppRegistry appRegistry;
    /** true → plain-http 环境：种无前缀非 Secure cookie(saas_c_session)；false(默认)→ __Host-+Secure。 */
    @Value("${saas.cookie.plain:false}")
    private boolean plainCookie;

    public OAuthImBindController(OAuthImBindApplicationService bindService, SaasUserSessionStore sessionStore,
                                 SaasAppRegistry appRegistry) {
        this.bindService = bindService;
        this.sessionStore = sessionStore;
        this.appRegistry = appRegistry;
    }

    /** H5 回调：授权码 + PKCE verifier -> 换 token -> 绑定 -> SaaS accessToken。 */
    @PostMapping("/oauth/im/callback")
    public AuthenticatedResponse callback(@RequestBody CallbackRequest request, HttpServletResponse response) {
        String resolvedAppId = bindService.resolveAppId(request.appId());
        appRegistry.requireType(resolvedAppId);
        BindResult result = bindService.bindByCode(request.code(), request.codeVerifier(), request.redirectUri(),
                resolvedAppId, request.state(), request.nonce());
        String sessionId = sessionStore.createSession(result.accountId(), resolvedAppId);
        String cookieName = appRegistry.cookieName(resolvedAppId, plainCookie);
        if (plainCookie) {
            SaasUserSessionCookie.setPlain(response, sessionId, cookieName);
        } else {
            SaasUserSessionCookie.set(response, sessionId, cookieName);
        }
        return new AuthenticatedResponse(true, result.accountId(), resolvedAppId);
    }

    /** 直接用 im_access_token 绑定（兼容旧入口）。 */
    @PostMapping("/oauth/im/bind")
    public AuthenticatedResponse bind(@RequestBody BindRequest request, HttpServletResponse response) {
        throw new ApiException(HttpStatusCodes.GONE, "LEGACY_BIND_DISABLED",
                "旧版直接绑定入口已停用，请改用 OIDC 授权码回调");
    }

    /** 解绑当前 app 的 OAuth 绑定并删除该会话；只影响目标 app。 */
    @PostMapping("/oauth/im/unbind")
    public void unbind(@RequestParam("app_id") String appId, HttpServletRequest request,
                       @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
                       HttpServletResponse response) {
        appRegistry.requireType(appId);
        String sessionId = sessionId(request, appId);
        if (!sessionStore.isCsrfTokenValid(sessionId, csrfToken)) {
            throw new ApiException(403, "CSRF_TOKEN_INVALID", "CSRF 令牌缺失或已过期");
        }
        SaasUserSessionStore.UserSession session = sessionStore.findSession(sessionId)
            .orElseThrow(() -> new ApiException(401, "SAAS_SESSION_REQUIRED", "登录状态已失效"));
        if (session.appId() != null && !session.appId().equals(appId)) {
            throw new ApiException(401, "SAAS_SESSION_REQUIRED", "应用与会话不匹配");
        }
        bindService.unbind(session.accountId(), appId);
        sessionStore.deleteSessions(session.accountId(), appId);
        SaasUserSessionCookie.clear(response, appRegistry.cookieName(appId, plainCookie));
    }

    private String sessionId(HttpServletRequest request, String appId) {
        if (request.getCookies() == null) {
            return null;
        }
        boolean backend = appRegistry.isBackend(appId);
        for (var cookie : request.getCookies()) {
            boolean match = backend
                ? SaasUserSessionCookie.B_NAME.equals(cookie.getName())
                    || SaasUserSessionCookie.B_PLAIN.equals(cookie.getName())
                : SaasUserSessionCookie.C_NAME.equals(cookie.getName())
                    || SaasUserSessionCookie.C_PLAIN.equals(cookie.getName());
            if (match) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public record CallbackRequest(@JsonProperty("code") String code,
                                  @JsonProperty("code_verifier") String codeVerifier,
                                  @JsonProperty("redirect_uri") String redirectUri,
                                  @JsonProperty("app_id") String appId,
                                  @JsonProperty("state") String state,
                                  @JsonProperty("nonce") String nonce) {}
    public record BindRequest(@JsonProperty("im_access_token") String imAccessToken) {
    }
    public record AuthenticatedResponse(boolean authenticated, Long accountId, String appId) {
        public AuthenticatedResponse(boolean authenticated) {
            this(authenticated, null, null);
        }
    }

}
