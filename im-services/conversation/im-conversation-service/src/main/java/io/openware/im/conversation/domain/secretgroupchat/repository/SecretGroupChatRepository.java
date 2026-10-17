package io.openware.im.conversation.domain.secretgroupchat.repository;

import io.openware.im.conversation.domain.secretgroupchat.model.SecretGroupChat;
import java.util.List;
import java.util.Optional;

public interface SecretGroupChatRepository {
  Persisted<SecretGroupChat> save(SecretGroupChat group);

  Optional<Persisted<SecretGroupChat>> findById(long id);

  Optional<Persisted<SecretGroupChat>> findByInviteToken(String token);

  List<Persisted<SecretGroupChat>> findByIds(List<Long> ids);

  void deleteById(long id);
}
