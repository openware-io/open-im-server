package io.openware.platform.tenant.domain.authorization;

/**
 * 角色绑定的作用域事实（用于把角色码归到某个租户/组织/门店作用域下）。
 *
 * <p>与 {@link RoleBinding} 的区别：后者是给运营人员列表展示的完整视图（含角色名、门店名、状态），
 * 而这里只带**归并所需的作用域键与角色码**。两个用途不同的投影不合并，避免展示型视图被迫
 * 承载聚合逻辑，也避免聚合查询被迫多取几列。
 *
 * @param tenantId       租户
 * @param organizationId 组织
 * @param storeId        门店
 * @param roleCode       角色编码
 */
public record RoleScope(
    Long tenantId,
    Long organizationId,
    Long storeId,
    String roleCode) {
}
