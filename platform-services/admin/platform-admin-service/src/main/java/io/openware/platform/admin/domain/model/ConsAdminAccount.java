package io.openware.platform.admin.domain.model;

/**
 * SaaS 后台管理员账号（聚合根）。
 *
 * <p>审计字段（createdBy/createdAt/updatedBy/updatedAt）不在此承载：它们是记账信息，由持久化适配器填充，
 * 见 standards/10“聚合装配与持久化契约”。此前本模型带有 createdAt/updatedAt，属遗留；写入路径的
 * created_at/updated_at 是 NOT NULL 无默认值列，统一由适配器负责。
 *
 * <p>状态迁移经具名方法完成，不提供整体设值方法：accountType/角色/IM 绑定各有其语义入口。
 *
 * @param id                聚合标识
 * @param username          登录名（唯一）
 * @param passwordHash      密码哈希（BCrypt），敏感字段：不得进日志、不得作为响应返回
 * @param displayName       展示名
 * @param imAccount         绑定的 IM 账号（同一账号唯一，解绑后为空）
 * @param idaasSubject      集团 IDaaS 主体（SSO 映射键）
 * @param platformAccountId 关联的 SaaS 平台账号 idt_account.id
 * @param role              角色
 * @param status            状态：ACTIVE / SUSPENDED / CLOSED
 */
public record ConsAdminAccount(
        Long id,
        String username,
        String passwordHash,
        String displayName,
        String imAccount,
        String idaasSubject,
        Long platformAccountId,
        AdminRole role,
        String status
) {
    /** 账号处于可用状态。 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 新建后台账号时使用的默认角色。 */
    public static final AdminRole DEFAULT_ROLE = AdminRole.TENANT_ADMIN;

    /**
     * 开通后台账号：状态固定为可用、角色取默认值，尚未落库（{@code id} 为空）。
     *
     * <p>状态与默认角色是开通用例的不变量，不由调用方逐字段赋值。
     */
    public static ConsAdminAccount provision(String username, String passwordHash, String displayName,
                                            String imAccount, Long platformAccountId) {
        return new ConsAdminAccount(null, username, passwordHash, displayName, imAccount, null,
                platformAccountId, DEFAULT_ROLE, STATUS_ACTIVE);
    }

    /** 账号是否处于可用状态。 */
    public boolean active() {
        return STATUS_ACTIVE.equalsIgnoreCase(status);
    }

    /**
     * 换绑 IM 账号：返回新实例，未落库账号拒绝。
     *
     * @throws IllegalStateException 账号尚未落库
     */
    public ConsAdminAccount withImAccount(String newImAccount) {
        requirePersisted();
        return new ConsAdminAccount(id, username, passwordHash, displayName, normalize(newImAccount),
                idaasSubject, platformAccountId, role, status);
    }

    /**
     * 迁移到另一个平台账号（换绑 IM 时账号口径随之改变）：返回新实例。
     *
     * @throws IllegalStateException 账号尚未落库
     */
    public ConsAdminAccount withPlatformAccountId(Long newPlatformAccountId) {
        requirePersisted();
        return new ConsAdminAccount(id, username, passwordHash, displayName, imAccount, idaasSubject,
                newPlatformAccountId, role, status);
    }

    /** 解绑 IM 账号：返回新实例，未落库账号拒绝。 */
    public ConsAdminAccount withoutImAccount() {
        requirePersisted();
        return new ConsAdminAccount(id, username, passwordHash, displayName, null, idaasSubject,
                platformAccountId, role, status);
    }

    /**
     * 迁移账号状态（启用/禁用）：返回新实例，未落库账号拒绝。
     *
     * @throws IllegalStateException 账号尚未落库，或状态为空
     */
    public ConsAdminAccount withStatus(String newStatus) {
        requirePersisted();
        if (newStatus == null || newStatus.isBlank()) {
            throw new IllegalStateException("staff account status must not be blank");
        }
        return new ConsAdminAccount(id, username, passwordHash, displayName, imAccount, idaasSubject,
                platformAccountId, role, newStatus);
    }

    /** 当前 IM 绑定是否可用于"换绑前先解绑"的判断：有绑定且与目标不同。 */
    public boolean hasConflictingImBinding(String targetImAccount) {
        return imAccount != null && !imAccount.isBlank()
                && !imAccount.equals(normalize(targetImAccount));
    }

    private void requirePersisted() {
        if (id == null) {
            throw new IllegalStateException("cannot change a staff account that is not persisted");
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
