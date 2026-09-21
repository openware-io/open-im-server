package com.gvchat.platform.tenant.domain.authorization;

import java.util.List;

/**
 * IAM 权限快照：账号在某作用域（tenant/organization/store）下的聚合权限码与授权版本。
 */
public record PermissionSnapshot(
        Long accountId,
        Long tenantId,
        Long organizationId,
        Long storeId,
        int authorizationVersion,
        List<String> permissions
) {
}
