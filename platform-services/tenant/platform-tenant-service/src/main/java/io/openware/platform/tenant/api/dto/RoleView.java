package io.openware.platform.tenant.api.dto;

/**
 * 角色响应（API 契约）。
 *
 * <p>此前接口直接返回持久化对象 {@code RolePo}。本类型固定对外形状，且按 standards/10 的 D2 规则
 * **不暴露**记账字段（createdBy/At、updatedBy/At）。
 */
public record RoleView(
    Long id,
    Long tenantId,
    String code,
    String name,
    String roleType,
    String scopeLevel,
    String domainCode,
    Long copyFromRoleId,
    String status) {
}
