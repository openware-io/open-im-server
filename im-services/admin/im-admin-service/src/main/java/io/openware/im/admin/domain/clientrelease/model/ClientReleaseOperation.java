package io.openware.im.admin.domain.clientrelease.model;

import java.time.LocalDateTime;

public record ClientReleaseOperation(
    Long id,
    String idempotencyKey,
    ReleaseOperationAction action,
    String requestDigest,
    Long releaseId,
    Long policyId,
    Long createdBy,
    LocalDateTime createdAt) {
}
