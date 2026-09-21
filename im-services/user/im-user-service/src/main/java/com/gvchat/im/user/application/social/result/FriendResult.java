package com.gvchat.im.user.application.social.result;

import java.time.LocalDateTime;

public record FriendResult(Long id, Long userId, Long friendId, String friendUsername, String friendNickname,
    String friendAvatar, String remark, String groupName, String status,
    Long createdBy, LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
}
