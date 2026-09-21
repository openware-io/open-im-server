package com.gvchat.im.message.application.result;

import java.time.LocalDateTime;

public record SyncedMessageResult(long syncSeq, MessageResult message, LocalDateTime readAt) {
}
