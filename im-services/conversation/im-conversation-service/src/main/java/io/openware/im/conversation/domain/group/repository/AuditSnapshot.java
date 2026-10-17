package io.openware.im.conversation.domain.group.repository;

import java.time.LocalDateTime;

/** 群组行的记账时间（created_at / updated_at），不是领域事实。 */
public record AuditSnapshot(LocalDateTime createdAt, LocalDateTime updatedAt) {
}
