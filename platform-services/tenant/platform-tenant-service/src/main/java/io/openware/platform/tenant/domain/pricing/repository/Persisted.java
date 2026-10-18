package io.openware.platform.tenant.domain.pricing.repository;

/** 聚合 + 该行的记账快照；审计列不进领域模型。 */
public record Persisted<T>(T aggregate, AuditSnapshot audit) {
}
