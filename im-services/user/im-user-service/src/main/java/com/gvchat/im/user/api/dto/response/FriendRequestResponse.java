package com.gvchat.im.user.api.dto.response;

import java.time.LocalDateTime;

public record FriendRequestResponse(Long id, Long fromUserId, Long toUserId, String message, String status,
    Long createdBy, LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
}
