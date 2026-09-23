package io.openware.im.user.domain.account.event;

import java.time.Instant;

public record UserAuthenticationInvalidated(
    String eventId,
    Long userId,
    long statusVersion,
    String correlationId,
    Instant occurredAt) {
}
