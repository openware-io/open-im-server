package io.openware.platform.admin.api.controller;

import io.openware.platform.admin.application.AdminPasswordApplicationService;
import io.openware.platform.admin.infra.security.AdminSessionCookie;
import io.openware.platform.admin.infra.security.AdminContextHolder;
import io.openware.platform.admin.infra.security.SaaAdminSessionStore;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SaaS 后台会话查询与退出（前端路由守卫据此判断登录态）。 */
@RestController
@RequestMapping("/admin/auth")
public class AdminSessionController {

    private final SaaAdminSessionStore sessionStore;
    private final AdminPasswordApplicationService passwordService;
    private final boolean plainCookie;

    public AdminSessionController(SaaAdminSessionStore sessionStore,
                                  AdminPasswordApplicationService passwordService,
                                  @Value("${saas.cookie.plain:false}") boolean plainCookie) {
        this.sessionStore = sessionStore;
        this.passwordService = passwordService;
        this.plainCookie = plainCookie;
    }

    @GetMapping("/session")
    public SessionResponse session(@CookieValue(value = AdminSessionCookie.NAME, required = false) String sessionId) {
        return sessionStore.findSession(sessionId)
                .map(ctx -> new SessionResponse(true, ctx.accountId(), ctx.username(),
                        ctx.displayName(), ctx.role().name(), sessionStore.selectedContextId(sessionId)))
                .orElse(new SessionResponse(false, null, null, null, null, null));
    }

    @PostMapping("/logout")
    public void logout(@CookieValue(value = AdminSessionCookie.NAME, required = false) String sessionId,
                       HttpServletResponse response) {
        sessionStore.deleteSession(sessionId);
        AdminSessionCookie.clear(response, plainCookie);
    }

    @PostMapping("/password")
    public PasswordChangeResponse changePassword(@CookieValue(value = AdminSessionCookie.NAME, required = false) String sessionId,
                                                  @org.springframework.web.bind.annotation.RequestBody PasswordChangeRequest request,
                                                  HttpServletResponse response) {
        passwordService.changePassword(AdminContextHolder.accountIdOrNull(), request.currentPassword(), request.newPassword());
        // 修改凭据后立即撤销当前会话，避免旧会话继续使用。
        sessionStore.deleteSession(sessionId);
        AdminSessionCookie.clear(response, plainCookie);
        return new PasswordChangeResponse(true);
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(@CookieValue(value = AdminSessionCookie.NAME, required = false) String sessionId) {
        return new CsrfResponse(sessionStore.issueCsrfToken(sessionId));
    }

    public record SessionResponse(boolean authenticated, Long accountId, String username,
                                  String displayName, String role, String selectedContextId) {}

    public record CsrfResponse(String csrfToken) {}

    public record PasswordChangeRequest(String currentPassword, String newPassword) {}

    public record PasswordChangeResponse(boolean changed) {}
}
