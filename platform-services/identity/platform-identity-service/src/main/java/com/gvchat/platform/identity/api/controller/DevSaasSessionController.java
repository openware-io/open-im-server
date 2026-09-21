package com.gvchat.platform.identity.api.controller;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.platform.identity.infra.client.TenantServiceClient;
import com.gvchat.platform.identity.infra.security.SaasUserSessionCookie;
import com.gvchat.platform.identity.infra.security.SaasUserSessionStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地/局域网调试放权：为受信任来源直接建立 H5 会话（跳过 PKCE 授权）。
 *
 * <p>背景：H5 的 OAuth 走 PKCE，依赖浏览器安全上下文（WebCrypto）。局域网设备用
 * {@code http://192.168.x.x:port/} 打开页面时不是安全上下文，{@code crypto.subtle} 不可用，
 * 正常授权链路无法完成；同时裸跳 IM {@code /oauth/authorize} 在桌面浏览器里也没有 IM 登录态。
 * 因此前端在 localhost/内网地址 + 调试端口下改为调用本端点换取会话（见 gv_saas_mobile 的
 * {@code ensureLocalDevSession}）。
 *
 * <p>安全边界（三重）：
 * <ol>
 *   <li>默认关闭，只有 {@code saas.dev-session.enabled=true} 时本 Bean 才存在（生产不注册路由）；</li>
 *   <li>只接受回环/内网来源地址（拒绝公网来源）；</li>
 *   <li>只接受白名单内的 H5 appId，并按应用族使用固定的调试账号（不接收调用方传入的账号）。</li>
 * </ol>
 * 绝不用于生产：正式环境既不开启开关，也不会有内网来源。
 */
@Slf4j
@RestController
@RequestMapping("/dev/saas")
@ConditionalOnProperty(name = "saas.dev-session.enabled", havingValue = "true")
public class DevSaasSessionController {

      /** 调试账号的授权范围：与 OAuth 回调写入的消费端授权保持一致。 */
    private static final String DEFAULT_SCOPE = "profile.basic";

    private final SaasUserSessionStore sessionStore;
    private final TenantServiceClient tenantServiceClient;
    private final boolean plainCookie;
    private final List<String> allowedAppIds;
    private final long consumerAccountId;
    private final long operatorAccountId;

    public DevSaasSessionController(SaasUserSessionStore sessionStore, TenantServiceClient tenantServiceClient,
                                    @Value("${saas.cookie.plain:false}") boolean plainCookie,
                                    @Value("${saas.dev-session.allowed-app-ids:saas-a380-c,saas-a380-h5}") String allowedAppIds,
                                    @Value("${saas.dev-session.consumer-account-id:0}") long consumerAccountId,
                                    @Value("${saas.dev-session.operator-account-id:0}") long operatorAccountId) {
        this.sessionStore = sessionStore;
        this.tenantServiceClient = tenantServiceClient;
        this.plainCookie = plainCookie;
        this.allowedAppIds = Arrays.stream(allowedAppIds.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
        this.consumerAccountId = consumerAccountId;
        this.operatorAccountId = operatorAccountId;
    }

    /** 建立调试会话：{@code POST /api/v1/dev/saas/session?appId=saas-a380-c|saas-a380-h5}。 */
    @PostMapping("/session")
    public DevSessionResponse create(@RequestParam(required = false) String appId,
                                     HttpServletRequest request, HttpServletResponse response) {
        String normalizedAppId = appId == null ? "" : appId.trim();
        if (!allowedAppIds.contains(normalizedAppId)) {
            throw new ApiException(HttpStatusCodes.FORBIDDEN, "DEV_SESSION_APP_FORBIDDEN",
                    "该应用未开放调试放权: " + normalizedAppId);
        }
        String remoteAddress = clientAddress(request);
        if (!isTrustedLanClient(remoteAddress)) {
            throw new ApiException(HttpStatusCodes.FORBIDDEN, "DEV_SESSION_SOURCE_FORBIDDEN",
                    "调试放权只允许回环或内网来源");
        }
        boolean operatorApp = isOperatorApp(normalizedAppId);
        long accountId = operatorApp ? operatorAccountId : consumerAccountId;
        if (accountId <= 0) {
            throw new ApiException(HttpStatusCodes.INTERNAL_SERVER_ERROR, "DEV_SESSION_ACCOUNT_MISSING",
                    "调试账号未配置（SAAS_DEV_SESSION_CONSUMER_ACCOUNT_ID / SAAS_DEV_SESSION_OPERATOR_ACCOUNT_ID）");
        }
        // 与 OAuth 回调一致：先补齐消费端应用授权，运营上下文才能解析出来。
        tenantServiceClient.grantConsumerAuthorization(accountId, normalizedAppId, DEFAULT_SCOPE);
        String sessionId = sessionStore.createSession(accountId, normalizedAppId);
        String cookieName = cookieNameFor(normalizedAppId, plainCookie);
        if (plainCookie) {
            SaasUserSessionCookie.setPlain(response, sessionId, cookieName);
        } else {
            SaasUserSessionCookie.set(response, sessionId, cookieName);
        }
        log.warn("dev-session issued: appId={}, accountId={}, source={}", normalizedAppId, accountId, remoteAddress);
        return new DevSessionResponse(true, accountId, normalizedAppId);
    }

    /**
     * 与 {@code OAuthImBindController.cookieNameFor} 同一规则：B 端应用族用 B cookie；
     * plain-http（本地 Kind）必须用无 {@code __Host-} 前缀的别名，否则浏览器会直接丢弃该 Cookie。
     */
    private static String cookieNameFor(String appId, boolean plainCookie) {
        boolean backendFamily = isOperatorApp(appId);
        if (plainCookie) {
            return backendFamily ? SaasUserSessionCookie.B_PLAIN : SaasUserSessionCookie.C_PLAIN;
        }
        return backendFamily ? SaasUserSessionCookie.B_NAME : SaasUserSessionCookie.C_NAME;
    }

    private static boolean isOperatorApp(String appId) {
        return appId.endsWith("-h5") || appId.contains("-b");
    }

    /** 取真实来源地址：优先 X-Forwarded-For 首段（网关转发），否则用 remoteAddr。 */
    private static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "" : request.getRemoteAddr();
    }

    /** 回环或 RFC1918 内网地址；其余（含公网）一律拒绝。 */
    private static boolean isTrustedLanClient(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        String value = address.startsWith("::ffff:") ? address.substring("::ffff:".length()) : address;
        if ("::1".equals(value) || "0:0:0:0:0:0:0:1".equals(value)) {
            return true;
        }
        if (value.startsWith("127.") || value.startsWith("10.") || value.startsWith("192.168.")) {
            return true;
        }
        if (value.startsWith("172.")) {
            String[] parts = value.split("\\.");
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    public record DevSessionResponse(boolean authenticated, long accountId, String appId) {
    }
}
