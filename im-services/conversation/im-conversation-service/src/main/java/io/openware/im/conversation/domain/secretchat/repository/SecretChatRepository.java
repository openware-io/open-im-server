package io.openware.im.conversation.domain.secretchat.repository;

import io.openware.im.conversation.domain.secretchat.model.SecretChat;
import java.util.List;
import java.util.Optional;

public interface SecretChatRepository {
  SecretChat save(SecretChat secretChat);

  Optional<SecretChat> findById(long id);

  Optional<SecretChat> findBetween(long userA, long userB);

  /** 统计两人之间已存在的私密会话数量（用于「最多 N 个」上限校验）。 */
  long countBetween(long userA, long userB);

  List<SecretChat> findByParticipant(long userId);

  /** 硬删除会话（任意一方删除即会话终止，双方列表都不再返回）。 */
  void deleteById(long id);
}
