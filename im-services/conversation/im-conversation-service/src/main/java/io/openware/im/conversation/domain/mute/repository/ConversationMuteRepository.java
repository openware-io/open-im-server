package io.openware.im.conversation.domain.mute.repository;

import io.openware.im.conversation.domain.mute.model.ConversationMute;
import java.util.List;

/** 会话免打扰持久化端口。 */
public interface ConversationMuteRepository {
  boolean existsByUserIdAndConversationId(long userId, String conversationId);

  List<ConversationMute> findByUserId(long userId);

  ConversationMute save(ConversationMute mute);

  void deleteByUserIdAndConversationId(long userId, String conversationId);
}
