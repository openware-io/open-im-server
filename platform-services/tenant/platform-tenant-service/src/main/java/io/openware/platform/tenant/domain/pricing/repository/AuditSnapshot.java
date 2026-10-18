package io.openware.platform.tenant.domain.pricing.repository;

import java.time.LocalDateTime;

/** 计价方案行的记账时间（created_at / updated_at），不是领域事实。 */
public record AuditSnapshot(LocalDateTime createdAt, LocalDateTime updatedAt) {
}
