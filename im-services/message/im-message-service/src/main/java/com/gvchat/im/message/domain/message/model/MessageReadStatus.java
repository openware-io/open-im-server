package com.gvchat.im.message.domain.message.model;

import java.time.LocalDateTime;

public record MessageReadStatus(String msgId, long userId, LocalDateTime readAt) {
  public static MessageReadStatus mark(String msgId, long userId, LocalDateTime readAt) {
    return new MessageReadStatus(msgId, userId, readAt);
  }
}
