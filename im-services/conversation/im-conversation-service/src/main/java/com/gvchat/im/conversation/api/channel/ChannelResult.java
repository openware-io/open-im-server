package com.gvchat.im.conversation.api.channel;

import java.time.LocalDateTime;

public record ChannelResult(Long id, String code, Long ownerId, String name, String avatar, String announcement,
    Long discussionGroupId, String status, String myRole, Boolean subscribed, Integer memberCount,
    LocalDateTime createdAt, LocalDateTime updatedAt) {
}
