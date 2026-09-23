package io.openware.platform.admin.domain.model;

import java.time.LocalDateTime;

/** SaaS 后台管理员账号（聚合根，认证侧只读）。 */
public record SaaAdminAccount(
        Long id,
        String username,
        String passwordHash,
        String displayName,
        String idaasSubject,
        Long platformAccountId,
        AdminRole role,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /** 兼容旧构造（无平台账号关联）。 */
    public SaaAdminAccount(Long id, String username, String passwordHash, String displayName,
                           String idaasSubject, AdminRole role, String status,
                           LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, username, passwordHash, displayName, idaasSubject, null, role, status, createdAt, updatedAt);
    }

    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 账号是否处于可用状态。 */
    public boolean active() {
        return STATUS_ACTIVE.equalsIgnoreCase(status);
    }
}
