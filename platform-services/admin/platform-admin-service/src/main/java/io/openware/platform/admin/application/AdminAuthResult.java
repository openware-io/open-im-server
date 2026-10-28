package io.openware.platform.admin.application;

import java.util.List;

/** SaaS 后台登录/SSO 交换结果：服务端会话 ID + 账号信息 + 角色工作台落点。 */
public record AdminAuthResult(String sessionId, AdminAccountView user, long expiresAt,
                              List<String> roles, List<String> workspaces,
                              String defaultWorkspace, String landing) {
    public AdminAuthResult(String sessionId, AdminAccountView user, long expiresAt) {
        this(sessionId, user, expiresAt, List.of(), List.of("PLATFORM"), "PLATFORM", "/select");
    }

    public AdminAuthResult(String sessionId, AdminAccountView user) {
        this(sessionId, user, 0);
    }
}
