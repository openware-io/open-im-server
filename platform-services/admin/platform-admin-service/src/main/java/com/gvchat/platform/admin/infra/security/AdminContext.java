package com.gvchat.platform.admin.infra.security;

import com.gvchat.platform.admin.domain.model.AdminRole;

/** SaaS 后台当前管理员上下文（由鉴权过滤器写入）。 */
public record AdminContext(Long accountId, String username, String displayName, AdminRole role,
                           Long platformAccountId, String tenantContextToken) {

    /** 兼容旧 4 参构造（无平台账号关联）。 */
    public AdminContext(Long accountId, String username, String displayName, AdminRole role) {
        this(accountId, username, displayName, role, null, null);
    }

    public AdminContext(Long accountId, String username, String displayName, AdminRole role,
                        Long platformAccountId) {
        this(accountId, username, displayName, role, platformAccountId, null);
    }
}
