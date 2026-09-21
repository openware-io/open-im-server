package com.gvchat.platform.identity.api.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gvchat.platform.identity.application.OAuthImBindApplicationService;
import com.gvchat.platform.identity.application.OAuthImBindApplicationService.BindResult;
import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.platform.identity.infra.security.SaasUserSessionCookie;
import com.gvchat.platform.identity.infra.security.SaasUserSessionStore;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
    /** true → plain-http 环境：种无前缀非 Secure cookie(saas_c_session)；false(默认)→ __Host-+Secure。 */
    @Value("${saas.cookie.plain:false}")
    private boolean plainCookie;

    public OAuthImBindController(OAuthImBindApplicationService bindService, SaasUserSessionStore sessionStore) {
        this.bindService = bindService;
        this.sessionStore = sessionStore;
    }

    /** H5 回调：授权码 + PKCE verifier -> 换 token -> 绑定 -> SaaS accessToken。 */
    @PostMapping("/oauth/im/callback")
    public AuthenticatedResponse callback(@RequestBody CallbackRequest request, HttpServletResponse response) {
        BindResult result = bindService.bindByCode(request.code(), request.codeVerifier(), request.redirectUri(),
                request.appId(), request.state(), request.nonce());
        String sessionId = sessionStore.createSession(result.accountId(), request.appId());
        String cookieName = cookieNameFor(request.appId());
        if (plainCookie) {
            SaasUserSessionCookie.setPlain(response, sessionId, cookieName);
        } else {
            SaasUserSessionCookie.set(response, sessionId, cookieName);
        }
        return new AuthenticatedResponse(true);
    }

    /** 直接用 im_access_token 绑定（兼容旧入口）。 */
    @PostMapping("/oauth/im/bind")
    public AuthenticatedResponse bind(@RequestBody BindRequest request, HttpServletResponse response) {
        throw new ApiException(HttpStatusCodes.GONE, "LEGACY_BIND_DISABLED",
                "旧版直接绑定入口已停用，请改用 OIDC 授权码回调");
    }

    public record CallbackRequest(@JsonProperty("code") String code,
                                  @JsonProperty("code_verifier") String codeVerifier,
                                  @JsonProperty("redirect_uri") String redirectUri,
                                  @JsonProperty("app_id") String appId,
                                  @JsonProperty("state") String state,
                                  @JsonProperty("nonce") String nonce) {}
    public record BindRequest(@JsonProperty("im_access_token") String imAccessToken) {
    }
    public record AuthenticatedResponse(boolean authenticated) {}

    private String cookieNameFor(String appId) {
        boolean backendFamily = appId != null && (appId.endsWith("-h5") || appId.contains("-b"));
        if (plainCookie) {
            return backendFamily ? SaasUserSessionCookie.B_PLAIN : SaasUserSessionCookie.C_PLAIN;
        }
        return backendFamily ? SaasUserSessionCookie.B_NAME : SaasUserSessionCookie.C_NAME;
    }
}
