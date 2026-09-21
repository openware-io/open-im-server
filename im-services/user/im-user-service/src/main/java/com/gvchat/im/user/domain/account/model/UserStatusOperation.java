package com.gvchat.im.user.domain.account.model;

import java.time.LocalDateTime;

public record UserStatusOperation(
    int requestVersion,
    long userId,
    UserAccountStatus previousStatus,
    UserAccountStatus currentStatus,
    long statusVersion,
    String idempotencyKey,
    String correlationId,
    LocalDateTime occurredAt) {
}
