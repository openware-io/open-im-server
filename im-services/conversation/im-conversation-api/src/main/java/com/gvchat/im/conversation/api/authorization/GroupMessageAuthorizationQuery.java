package com.gvchat.im.conversation.api.authorization;

import java.time.Instant;

public record GroupMessageAuthorizationQuery(long conversationId, long userId, String commandId, Instant requestedAt) {
}
