package com.gvchat.im.user.domain.social.event;

public record FriendAccepted(Long requestId, Long fromUserId, Long toUserId) {
}
