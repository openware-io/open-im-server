package io.openware.platform.admin.application;

/** SaaS 后台登录/SSO 交换结果：服务端会话 ID（Redis）+ 账号信息。 */
public record AdminAuthResult(String sessionId, AdminAccountView user, long expiresAt) {
    public AdminAuthResult(String sessionId, AdminAccountView user) {
        this(sessionId, user, 0);
    }
}
