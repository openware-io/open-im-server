package io.openware.im.message.domain.message.repository;

import java.time.LocalDateTime;

/**
 * 记账快照（普通消息的四个审计列）。
 *
 * <p>审计列是**记账信息**、不是业务事实（standards/10 L299），因此不进领域模型；但响应
 * （{@code toResult}）要回显它们，编辑窗口规则也要读发送时刻，所以由仓储端口在读回业务聚合的同时
 * 把这份快照一并交给上层。
 *
 * <p>与 {@code io.openware.im.message.domain.secretmessage.repository.AuditSnapshot} 同形——
 * 每个端口自带自己的快照类型，不跨上下文共用。
 *
 * @param createdBy 创建人（{@code created_by}）
 * @param createdAt 创建时间（{@code created_at}），也是消息发送时刻
 * @param updatedBy 最后更新人（{@code updated_by}）
 * @param updatedAt 最后更新时间（{@code updated_at}）
 */
public record AuditSnapshot(Long createdBy, LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
}
