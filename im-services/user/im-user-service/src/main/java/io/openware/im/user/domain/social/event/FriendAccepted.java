package io.openware.im.user.domain.social.event;

public record FriendAccepted(Long requestId, Long fromUserId, Long toUserId) {
}
