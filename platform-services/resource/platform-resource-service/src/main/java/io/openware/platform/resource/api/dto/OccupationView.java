package io.openware.platform.resource.api.dto;

import java.time.LocalDateTime;

/**
 * 资源占用响应（API 契约）。
 *
 * <p>此前接口直接返回持久化对象 {@code OccupationPo}，使对外契约与表映射耦合。本类型固定对外形状，
 * 且按 standards/10 的 D2 规则**不暴露**记账字段（createdBy/At、updatedBy/At）。
 *
 * <p>{@code version} 保留（乐观锁并发语义，调用方需要它做后续 CAS）；{@code holdExpiresAt}、
 * {@code startAt}/{@code endAt} 是业务时间，保留。
 */
public record OccupationView(
    Long id,
    Long tenantId,
    Long storeId,
    Long resourceId,
    String sourceType,
    Long sourceId,
    LocalDateTime startAt,
    LocalDateTime endAt,
    String status,
    LocalDateTime holdExpiresAt,
    Integer version) {
}
