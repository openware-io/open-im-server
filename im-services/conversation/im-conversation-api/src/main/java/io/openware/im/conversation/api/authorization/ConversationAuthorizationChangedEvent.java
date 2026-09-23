package io.openware.im.conversation.api.authorization;

import java.time.Instant;

public record ConversationAuthorizationChangedEvent(
    String eventId,
    int eventVersion,
    Instant occurredAt,
    long conversationId,
    long userId,
    long affectedUserId,
    String changeType,
    String groupStatus,
    String memberStatus,
    String memberRole,
    Instant mutedUntil,
    long authorizationVersion) {
}
