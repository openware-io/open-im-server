package io.openware.platform.tenant.api.dto;

import java.time.LocalDateTime;

/**
 * 账号-角色绑定响应（API 契约）。
 *
 * <p>此前接口直接返回持久化对象 {@code UserRolePo}。本类型固定对外形状，且按 standards/10 的 D2 规则
 * **不暴露**记账字段（createdBy/At、updatedBy/At）。
 */
public record UserRoleView(
    Long id,
    Long accountId,
    Long tenantId,
    Long organizationId,
    Long storeId,
    Long roleId,
    String scopeType,
    LocalDateTime effectiveFrom,
    LocalDateTime effectiveTo,
    String status,
    Integer authorizationVersion) {
}
