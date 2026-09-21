package com.gvchat.im.message.domain.message.model;

import java.time.LocalDateTime;

public record UserSyncIndex(long userId, long syncSeq, String msgId, String conversationId, LocalDateTime createdAt) {
}
