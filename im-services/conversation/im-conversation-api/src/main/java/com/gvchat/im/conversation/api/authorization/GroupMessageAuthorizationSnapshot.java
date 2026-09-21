package com.gvchat.im.conversation.api.authorization;

import java.time.Instant;

public record GroupMessageAuthorizationSnapshot(
    long conversationId,
    long userId,
    String groupStatus,
    String memberStatus,
    String memberRole,
    Instant mutedUntil,
    long authorizationVersion,
    boolean allowed,
    String denialCode) {
}
