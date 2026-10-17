package io.openware.im.message.domain.secretgroupmessage.repository;

import java.time.LocalDateTime;

/**
 * 记账快照（私密群聊消息的四个审计列）。
 *
 * <p>审计列是**记账信息**、不是业务事实（standards/10），因此不进领域模型；但响应
 * （{@code toResult}）要回显它们，所以由仓储端口在读回业务聚合的同时把这份快照一并交给上层。
 *
 * <p>与 {@code io.openware.platform.order.domain.order.repository.AuditSnapshot} 同形——
 * 每个端口自带自己的快照类型，不跨上下文共用。
 *
 * @param createdBy 创建人（{@code created_by}）
 * @param createdAt 创建时间（{@code created_at}）
 * @param updatedBy 最后更新人（{@code updated_by}）
 * @param updatedAt 最后更新时间（{@code updated_at}）
 */
public record AuditSnapshot(Long createdBy, LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
}
