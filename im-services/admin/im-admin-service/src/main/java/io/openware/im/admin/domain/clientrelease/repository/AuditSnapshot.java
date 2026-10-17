package io.openware.im.admin.domain.clientrelease.repository;

import java.time.LocalDateTime;

/** 策略行的记账时间（created_at / updated_at），不是领域事实。 */
public record AuditSnapshot(LocalDateTime createdAt, LocalDateTime updatedAt) {
}
