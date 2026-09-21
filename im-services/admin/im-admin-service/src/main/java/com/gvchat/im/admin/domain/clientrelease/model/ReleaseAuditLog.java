package com.gvchat.im.admin.domain.clientrelease.model;

import java.time.LocalDateTime;

public record ReleaseAuditLog(
    Long id,
    Long releaseId,
    Long policyId,
    ReleaseOperationAction action,
    ReleaseStatus beforeStatus,
    ReleaseStatus afterStatus,
    String requestId,
    String idempotencyKey,
    String reason,
    String payloadDigest,
    Long createdBy,
    LocalDateTime createdAt) {
}
