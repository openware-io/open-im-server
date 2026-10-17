package io.openware.common.payment.domain.channel.repository;

import java.time.LocalDateTime;

/** 渠道配置行的记账时间（created_at / updated_at），不是领域事实。 */
public record AuditSnapshot(LocalDateTime createdAt, LocalDateTime updatedAt) {
}
