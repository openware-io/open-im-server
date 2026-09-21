package com.gvchat.platform.tenant.domain.authorization;

/**
 * 账号角色绑定视图：iam_user_role 聚合出的角色绑定（角色名 + 作用域 + 门店/租户名 + 状态）。
 * 供 SaaS 后台运营人员列表展示"已绑定角色/作用域"。
 */
public record RoleBinding(
        Long roleId,
        Long tenantId,
        Long organizationId,
        Long storeId,
        String roleCode,
        String roleName,
        String scopeType,
        String tenantName,
        String storeName,
        String status
) {
}
