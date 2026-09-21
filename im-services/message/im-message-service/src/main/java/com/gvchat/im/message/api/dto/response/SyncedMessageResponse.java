package com.gvchat.im.message.api.dto.response;

import java.time.Instant;

public record SyncedMessageResponse(long syncSeq, MessageResponse message, Instant readAt) {
}
