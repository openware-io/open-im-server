package io.openware.im.message.domain.message.port;

import java.time.LocalDateTime;
import java.util.List;

/** 私密消息的每接收方未读投影；只保存消息标识和序号，绝不保存密文或明文。 */
public interface SecretUnreadProjectionPort {
  void record(String chatType, long conversationId, long userId, String msgId, long seq, LocalDateTime occurredAt);

  void markRead(String chatType, long conversationId, long userId, long afterSeq);

  void deleteByMessage(String chatType, long conversationId, String msgId);

  long countForUser(long userId, boolean secretChatEnabled, boolean secretGroupChatEnabled);

  List<ConversationUnread> countByConversationForUser(long userId, boolean secretChatEnabled,
      boolean secretGroupChatEnabled);

  record ConversationUnread(String conversationId, long count) {
  }
}
