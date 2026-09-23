package io.openware.infrastructure.tenant;

import java.util.List;

/**
 * 租户上下文：仅由签名 tenantContextToken 与 IAM 查询生成，禁止从 URL/Header/请求体直接信任。
 *
 * <p><b>作用域（scopeType）</b>：平台账号（运营）签发的上下文带 {@code PLATFORM}，其
 * {@code tenantId} 允许为 {@code 0}——表示「平台级动作，没有租户约束」（如创建租户）。租户账号
 * 的上下文必须 {@code tenantId > 0}；该约束由 {@link TenantContextFilter} 在验签之后强制，
 * 客户端无法自行声明。
 */
public record TenantContext(
        long tenantId,
        Long organizationId,
        Long storeId,
        long accountId,
        int authorizationVersion,
        List<String> permissions,
        String scopeType
) {
    public static final String HEADER = "X-Tenant-Context";

    /** 平台作用域：平台运营上下文，允许 tenantId=0（无租户约束）。 */
    public static final String SCOPE_PLATFORM = "PLATFORM";

    /** 兼容旧 5 参构造：权限与作用域缺省。 */
    public TenantContext(long tenantId, Long organizationId, Long storeId, long accountId, int authorizationVersion) {
        this(tenantId, organizationId, storeId, accountId, authorizationVersion, List.of(), null);
    }

    /** 兼容旧 6 参构造：作用域缺省（按租户上下文处理）。 */
    public TenantContext(long tenantId, Long organizationId, Long storeId, long accountId, int authorizationVersion,
                         List<String> permissions) {
        this(tenantId, organizationId, storeId, accountId, authorizationVersion, permissions, null);
    }

    /** 是否平台作用域上下文（{@code tenantId} 允许为 0）。 */
    public boolean platformScope() {
        return SCOPE_PLATFORM.equals(scopeType);
    }
}
