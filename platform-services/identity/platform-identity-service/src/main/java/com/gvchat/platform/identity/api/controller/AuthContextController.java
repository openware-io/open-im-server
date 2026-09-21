package com.gvchat.platform.identity.api.controller;

import com.gvchat.platform.identity.api.dto.AuthContextDtos.ContextItem;
import com.gvchat.platform.identity.api.dto.AuthContextDtos.ContextsResponse;
import com.gvchat.platform.identity.api.dto.AuthContextDtos.SelectContextRequest;
import com.gvchat.platform.identity.api.dto.AuthContextDtos.SelectContextResponse;
import com.gvchat.platform.identity.application.AuthContextApplicationService;
import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.platform.identity.infra.security.SaasUserSessionCookie;
import com.gvchat.platform.identity.infra.security.SaasUserSessionStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** SaaS 经营上下文选择：/auth/contexts + /auth/context/select（网关 /api/v1/auth/** 单独路由到本服务）。
 *  从 HttpOnly Cookie 对应的服务端会话解析 accountId；浏览器不持有 access token。 */
@RestController
@RequestMapping("/auth")
public class AuthContextController {
    private final AuthContextApplicationService service;
    private final SaasUserSessionStore sessionStore;

    public AuthContextController(AuthContextApplicationService service, SaasUserSessionStore sessionStore) {
        this.service = service;
        this.sessionStore = sessionStore;
    }

    @GetMapping("/contexts")
    public ContextsResponse contexts(@RequestParam(value = "appId", required = false) String appId,
                                     HttpServletRequest request) {
        SaasUserSessionStore.UserSession userSession = session(request, appId);
        List<ContextItem> items = service.contexts(userSession.accountId(), userSession.appId());
        return new ContextsResponse(items);
    }

    @PostMapping("/context/select")
    public ContextSelectedResponse select(@RequestParam(value = "appId", required = false) String appId,
                                         HttpServletRequest servletRequest,
                                        @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
                                        @RequestBody SelectContextRequest request) {
        String sessionId = sessionId(servletRequest, appId);
        SaasUserSessionStore.UserSession session = session(servletRequest, appId);
        if (!sessionStore.isCsrfTokenValid(sessionId, csrfToken)) {
            throw new ApiException(HttpStatusCodes.FORBIDDEN, "CSRF_TOKEN_INVALID", "CSRF 令牌缺失或已过期");
        }
        SelectContextResponse selected = service.select(session.accountId(), request.contextId(), session.appId());
        sessionStore.setActiveContext(sessionId, selected.tenantContextToken());
        return new ContextSelectedResponse(true, selected.expiresAt(), selected.tenantId(),
                selected.organizationId(), selected.storeId(), selected.authorizationVersion(), selected.permissions(),
                selected.currencyCode());
    }

    @GetMapping("/session")
    public SessionResponse sessionStatus(@RequestParam(value = "appId", required = false) String appId,
                                         HttpServletRequest request) {
        return sessionStore.findSession(sessionId(request, appId))
                .map(session -> new SessionResponse(true, session.accountId(), session.appId()))
                .orElse(new SessionResponse(false, null, null));
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(@RequestParam(value = "appId", required = false) String appId,
                             HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        return new CsrfResponse(sessionStore.issueCsrfToken(sessionId(request, appId)));
    }

    @PostMapping("/logout")
    public void logout(@RequestParam(value = "appId", required = false) String appId,
                       HttpServletRequest request,
                       @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
                       HttpServletResponse response) {
        String sessionId = sessionId(request, appId);
        if (!sessionStore.isCsrfTokenValid(sessionId, csrfToken)) {
            throw new ApiException(HttpStatusCodes.FORBIDDEN, "CSRF_TOKEN_INVALID", "CSRF 令牌缺失或已过期");
        }
        sessionStore.deleteSession(sessionId);
        SaasUserSessionCookie.clear(response, sessionCookieName(request));
    }

    private SaasUserSessionStore.UserSession session(HttpServletRequest request, String appId) {
        return sessionStore.findSession(sessionId(request, appId)).orElseThrow(() ->
                new ApiException(HttpStatusCodes.UNAUTHORIZED, "SAAS_SESSION_REQUIRED", "登录状态已失效，请重新登录"));
    }

    /**
     * 按调用方声明的应用族解析会话 cookie。
     *
     * <p>**明确声明了 appId 时绝不跨族兜底**：B 端（运营后台）请求若因为本机只有 C 端会话就拿到 C 端会话，
     * 页面会「以为已登录」而不去走 OAuth，结果运营动作全部 403（真机实测：
     * `POST /business/reservations/{id}/confirm` → `PERMISSION_DENIED 缺少权限: reservation.confirm`）。
     * 如实返回未登录，页面才会重新授权并换成正确族的会话。
     *
     * <p>未声明 appId 时保持既有兼容顺序（C 优先，其次任意会话 cookie）。
     */
    private static String sessionId(HttpServletRequest request, String appId) {
        if (request.getCookies() == null) {
            return null;
        }
        if (appId == null || appId.isBlank()) {
            String fallback = null;
            for (var cookie : request.getCookies()) {
                if (isCFamily(cookie.getName())) {
                    return cookie.getValue();
                }
                if (fallback == null && isSessionCookieName(cookie.getName())) {
                    fallback = cookie.getValue();
                }
            }
            return fallback;
        }
        boolean backend = isBackendApp(appId);
        for (var cookie : request.getCookies()) {
            boolean matches = backend ? isBFamily(cookie.getName()) : isCFamily(cookie.getName());
            if (matches) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /** B 端应用族判定：`*-h5` 或含 `-b`（与网关、OAuth 回调同一规则）。 */
    private static boolean isBackendApp(String appId) {
        return appId.endsWith("-h5") || appId.contains("-b");
    }

    private static boolean isBFamily(String name) {
        return SaasUserSessionCookie.B_NAME.equals(name) || SaasUserSessionCookie.B_PLAIN.equals(name);
    }

    private static boolean isCFamily(String name) {
        return SaasUserSessionCookie.C_NAME.equals(name) || SaasUserSessionCookie.C_PLAIN.equals(name);
    }

    private static String sessionCookieName(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                // 优先 B 族（-h5/-b 后端应用），其次 C 族；两组名字都接受。
                if (SaasUserSessionCookie.B_NAME.equals(cookie.getName())
                        || SaasUserSessionCookie.B_PLAIN.equals(cookie.getName())) {
                    return cookie.getName();
                }
            }
            for (var cookie : request.getCookies()) {
                if (isSessionCookieName(cookie.getName())) {
                    return cookie.getName();
                }
            }
        }
        return SaasUserSessionCookie.C_NAME;
    }

    private static boolean isSessionCookieName(String name) {
        return SaasUserSessionCookie.C_NAME.equals(name)
                || SaasUserSessionCookie.B_NAME.equals(name)
                || SaasUserSessionCookie.C_PLAIN.equals(name)
                || SaasUserSessionCookie.B_PLAIN.equals(name);
    }

    public record SessionResponse(boolean authenticated, Long accountId, String appId) {
        public SessionResponse(boolean authenticated, Long accountId) {
            this(authenticated, accountId, null);
        }
    }
    public record CsrfResponse(String csrfToken) {}
    /** {@code currencyCode} 与 JWT claim {@code currency} 同源同值（规范 §3.1/§3.2）。 */
    public record ContextSelectedResponse(boolean selected, long expiresAt, Long tenantId, Long organizationId,
                                          Long storeId, int authorizationVersion, List<String> permissions,
                                          String currencyCode) {}
}
