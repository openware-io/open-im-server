package io.openware.im.user.api.authorization;

import java.time.Instant;

public record PrivateMessageAuthorizationQuery(long userId, long peerUserId, String commandId, Instant requestedAt) {
}
