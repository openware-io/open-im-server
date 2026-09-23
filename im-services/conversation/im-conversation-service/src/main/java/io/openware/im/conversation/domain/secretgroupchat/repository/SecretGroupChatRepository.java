package io.openware.im.conversation.domain.secretgroupchat.repository;

import io.openware.im.conversation.domain.secretgroupchat.model.SecretGroupChat;
import java.util.List;
import java.util.Optional;

public interface SecretGroupChatRepository {
  SecretGroupChat save(SecretGroupChat group);

  Optional<SecretGroupChat> findById(long id);

  Optional<SecretGroupChat> findByInviteToken(String token);

  List<SecretGroupChat> findByIds(List<Long> ids);

  void deleteById(long id);
}
