package io.openware.protocol.mq.event;

import java.time.Instant;

public record UserAuthenticationInvalidatedEvent(
    String eventId,
    Long userId,
    long authenticationVersion,
    Instant occurredAt) {
}
