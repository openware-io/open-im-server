package com.gvchat.platform.admin.api.controller;

import com.gvchat.platform.admin.application.AdminAuthResult;
import com.gvchat.platform.admin.application.AdminLoginApplicationService;
import com.gvchat.platform.admin.infra.security.AdminSessionCookie;
import com.gvchat.platform.admin.infra.security.SessionTtl;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;

/** SaaS 后台账号密码登录（非 SSO）：创建 Redis 会话。 */
@RestController
@RequestMapping("/admin/auth")
public class AdminLoginController {

    private final AdminLoginApplicationService loginService;
    private final boolean plainCookie;

    public AdminLoginController(AdminLoginApplicationService loginService,
                                @Value("${saas.cookie.plain:false}") boolean plainCookie) {
        this.loginService = loginService;
        this.plainCookie = plainCookie;
    }

    @PostMapping("/login")
    public AdminAuthResult login(@RequestBody LoginRequest request, HttpServletResponse response) {
        Duration ttl = SessionTtl.resolve(request.ttlHours());
        AdminAuthResult result = loginService.login(request.username(), request.password(), ttl);
        AdminSessionCookie.set(response, result.sessionId(), ttl, plainCookie);
        return result;
    }

    public record LoginRequest(String username, String password, Integer ttlHours) {
    }
}
