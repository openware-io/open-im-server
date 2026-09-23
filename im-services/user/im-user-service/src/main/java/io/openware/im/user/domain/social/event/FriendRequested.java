package io.openware.im.user.domain.social.event;

public record FriendRequested(Long requestId, Long fromUserId, Long toUserId, String message) {
}
