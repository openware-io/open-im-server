package com.gvchat.protocol.mq.event;

import java.time.Instant;
import java.util.List;

public record MessageEditedEvent(
    String eventId,
    String msgId,
    String conversationId,
    long seq,
    long senderId,
    String chatType,
    String toId,
    String content,
    List<Long> recipientUserIds,
    Instant editedAt) {}
