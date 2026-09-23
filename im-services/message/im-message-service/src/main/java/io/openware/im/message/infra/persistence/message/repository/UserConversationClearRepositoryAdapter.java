package io.openware.im.message.infra.persistence.message.repository;

import io.openware.im.message.domain.message.repository.UserConversationClearRepository;
import io.openware.im.message.infra.persistence.message.mapper.UserConversationClearMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserConversationClearRepositoryAdapter implements UserConversationClearRepository {
  private final UserConversationClearMapper mapper;

  @Override
  public void markCleared(long userId, String conversationId, String chatType, LocalDateTime clearedAt,
      long clearedBy) {
    mapper.upsert(userId, conversationId, chatType, clearedAt, clearedBy);
  }

  @Override
  public List<ClearedConversation> findByUserId(long userId) {
    return mapper.findByUserId(userId).stream()
        .map(po -> new ClearedConversation(po.getConversationId(), po.getChatType(), po.getClearedAt()))
        .toList();
  }
}
