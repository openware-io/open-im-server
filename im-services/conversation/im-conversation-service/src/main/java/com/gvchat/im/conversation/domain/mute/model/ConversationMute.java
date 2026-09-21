package com.gvchat.im.conversation.domain.mute.model;

import java.time.LocalDateTime;

/** 用户对某个会话的免打扰设置；仅「已免打扰」的会话存在记录，取消免打扰即删除记录。 */
public record ConversationMute(Long id, long userId, String conversationId, LocalDateTime createdAt) {

  public static ConversationMute create(long userId, String conversationId, LocalDateTime occurredAt) {
    return new ConversationMute(null, userId, conversationId, occurredAt);
  }
}
