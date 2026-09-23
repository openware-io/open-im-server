package io.openware.im.user.domain.account.event;

import io.openware.im.user.domain.account.model.UserAccountStatus;
import java.time.Instant;

public record UserStatusChanged(
    String eventId,
    Long userId,
    UserAccountStatus previousStatus,
    UserAccountStatus status,
    long statusVersion,
    Long operatorId,
    String reason,
    String correlationId,
    Instant occurredAt) {
}
