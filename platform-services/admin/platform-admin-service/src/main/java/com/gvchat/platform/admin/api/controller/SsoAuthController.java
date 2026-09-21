package com.gvchat.platform.admin.api.controller;

import com.gvchat.platform.admin.application.AdminAuthResult;
import com.gvchat.platform.admin.application.SsoAuthApplicationService;
import com.gvchat.platform.admin.infra.security.AdminSessionCookie;
import com.gvchat.platform.admin.infra.security.SessionTtl;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;

/**
 * SaaS 后台 SSO 免二次登录：用一次性票据换取 Redis 会话（HttpOnly Cookie）。
 * 网关路径 /api/v1/admin/auth/sso → 本服务 /admin/auth/sso。
 */
@RestController
@RequestMapping("/admin/auth")
public class SsoAuthController {
    private final SsoAuthApplicationService ssoAuthService;
    private final boolean plainCookie;

    public SsoAuthController(SsoAuthApplicationService ssoAuthService,
                             @Value("${saas.cookie.plain:false}") boolean plainCookie) {
        this.ssoAuthService = ssoAuthService;
        this.plainCookie = plainCookie;
    }

    @PostMapping("/sso")
    public AdminAuthResult sso(@RequestBody SsoAuthRequest request, HttpServletResponse response) {
        // 继承集团会话剩余时长，只用本后台上限封顶（曾经用 SessionTtl.SSO=1 天当上限，把门户选的
        // 7 天 / 30 天截断了）；票据拿不到到期时间时才回退 SSO 兜底值。
        AdminAuthResult result = ssoAuthService.exchange(request.ticket(), SessionTtl.MAX);
        Duration ttl = result.expiresAt() > 0
            ? Duration.ofMillis(Math.max(1, result.expiresAt() - System.currentTimeMillis()))
            : SessionTtl.SSO;
        AdminSessionCookie.set(response, result.sessionId(), ttl, plainCookie);
        return result;
    }

    public record SsoAuthRequest(String ticket) {}
}
