package io.openware.im.user.application.social.result;

import java.time.LocalDateTime;

public record FriendRequestResult(Long id, Long fromUserId, Long toUserId, String message, String status,
    Long createdBy, LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
}
