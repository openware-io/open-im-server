package io.openware.platform.tenant.api.dto;

/**
 * 权限点响应（API 契约）。
 *
 * <p>此前接口直接返回持久化对象 {@code PermissionPo}。本类型固定对外形状，且按 standards/10 的 D2 规则
 * **不暴露**记账字段（createdBy/At、updatedBy/At）。
 */
public record PermissionView(
    Long id,
    String code,
    String module,
    String resource,
    String action,
    String description,
    String scopeLevel,
    String domainCode,
    String grantableLevels,
    String menuCode,
    String status) {
}
