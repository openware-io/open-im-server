package io.openware.im.conversation.application.mute;

import io.openware.im.conversation.domain.mute.model.ConversationMute;
import io.openware.im.conversation.domain.mute.repository.ConversationMuteRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 单会话免打扰应用服务：持久化用户的会话级免打扰，供跨端同步与离线推送过滤。 */
@Service
@RequiredArgsConstructor
public class ConversationMuteApplicationService {
  private final ConversationMuteRepository conversationMuteRepository;

  @Transactional
  public boolean setMuted(long userId, String conversationId, boolean muted) {
    if (muted) {
      if (!conversationMuteRepository.existsByUserIdAndConversationId(userId, conversationId)) {
        conversationMuteRepository.save(ConversationMute.create(userId, conversationId, LocalDateTime.now()));
      }
      return true;
    }
    conversationMuteRepository.deleteByUserIdAndConversationId(userId, conversationId);
    return false;
  }

  @Transactional(readOnly = true)
  public List<String> getMutedConversationIds(long userId) {
    return conversationMuteRepository.findByUserId(userId).stream()
        .map(ConversationMute::conversationId).toList();
  }

  @Transactional(readOnly = true)
  public boolean isMuted(long userId, String conversationId) {
    return conversationMuteRepository.existsByUserIdAndConversationId(userId, conversationId);
  }
}
