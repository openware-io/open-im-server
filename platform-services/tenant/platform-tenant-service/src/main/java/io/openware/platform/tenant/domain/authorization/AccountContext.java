package io.openware.platform.tenant.domain.authorization;

import java.util.List;

/**
 * 账号可访问的经营上下文（按 iam_user_role 的 tenant/organization/store 聚合）。
 */
public record AccountContext(
        String contextId,
        Long tenantId,
        String tenantName,
        Long organizationId,
        String organizationName,
        Long storeId,
        String storeName,
        List<String> roles,
        String scopeType
) {
}
