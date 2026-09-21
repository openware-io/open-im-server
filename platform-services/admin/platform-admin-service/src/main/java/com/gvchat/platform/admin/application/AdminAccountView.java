package com.gvchat.platform.admin.application;

/** SaaS 后台账号视图（SSO 交换与账号密码登录统一返回）。 */
public record AdminAccountView(Long accountId, String username, String displayName, String role, Long platformAccountId) {
    /** 兼容旧构造。 */
    public AdminAccountView(Long accountId, String username, String displayName, String role) {
        this(accountId, username, displayName, role, null);
    }
}
