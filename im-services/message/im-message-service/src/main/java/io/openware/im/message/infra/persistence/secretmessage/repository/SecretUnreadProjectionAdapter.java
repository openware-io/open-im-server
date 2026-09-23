package io.openware.im.message.infra.persistence.secretmessage.repository;

import io.openware.im.message.domain.message.port.SecretUnreadProjectionPort;
import io.openware.im.message.infra.persistence.secretmessage.mapper.SecretUnreadMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SecretUnreadProjectionAdapter implements SecretUnreadProjectionPort {
  private final SecretUnreadMapper mapper;

  @Override
  public void record(String chatType, long conversationId, long userId, String msgId, long seq, LocalDateTime occurredAt) {
    mapper.insert(chatType, conversationId, userId, msgId, seq, occurredAt);
  }

  @Override
  public void markRead(String chatType, long conversationId, long userId, long afterSeq) {
    mapper.markRead(chatType, conversationId, userId, afterSeq);
  }

  @Override
  public void deleteByMessage(String chatType, long conversationId, String msgId) {
    mapper.deleteByMessage(chatType, conversationId, msgId);
  }

  @Override
  public long countForUser(long userId, boolean secretChatEnabled, boolean secretGroupChatEnabled) {
    return mapper.countForUser(userId, secretChatEnabled, secretGroupChatEnabled);
  }

  @Override
  public List<ConversationUnread> countByConversationForUser(long userId, boolean secretChatEnabled,
      boolean secretGroupChatEnabled) {
    return mapper.countByConversationForUser(userId, secretChatEnabled, secretGroupChatEnabled).stream()
        .map(row -> new ConversationUnread(String.valueOf(row.get("conversation_id")),
            ((Number) row.get("cnt")).longValue()))
        .toList();
  }
}
