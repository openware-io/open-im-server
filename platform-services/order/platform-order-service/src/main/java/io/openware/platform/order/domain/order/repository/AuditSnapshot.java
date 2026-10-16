package io.openware.platform.order.domain.order.repository;

import java.time.LocalDateTime;

/**
 * 记账快照（订单/明细的四个审计列）。
 *
 * <p>审计列是**记账信息**、不是业务事实（standards/10），因此不进领域模型；但读路径的响应与报表确实需要它们
 * （列表要回显 {@code createdAt}，员工业绩报表要按 {@code created_by} 归集「开单员工」），
 * 所以由仓储端口在读回业务聚合的同时把这份快照一并交给上层：只需要业务事实的调用方读聚合，
 * 需要装配响应/报表的调用方读本快照。
 *
 * <p>与 {@code ReservationRepository.AuditSnapshot} 同形：每个端口自带自己的快照类型，
 * 不跨上下文共用（预约与订单是两条独立的持久化契约）。
 *
 * @param createdBy 创建人（{@code created_by}；库中可能是 DDL 默认 0 = 系统）
 * @param createdAt 创建时间（{@code created_at}）
 * @param updatedBy 最后更新人（{@code updated_by}）
 * @param updatedAt 最后更新时间（{@code updated_at}）
 */
public record AuditSnapshot(Long createdBy, LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
}
