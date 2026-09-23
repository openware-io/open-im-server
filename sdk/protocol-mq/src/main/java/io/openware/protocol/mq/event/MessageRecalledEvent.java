package io.openware.protocol.mq.event;

import java.time.Instant;
import java.util.List;

public record MessageRecalledEvent(
    String eventId,
    int eventVersion,
    Instant recalledAt,
    String msgId,
    String conversationId,
    String chatType,
    String toId,
    long recalledByUserId,
    List<Long> recipientUserIds,
    String reason) {
}
