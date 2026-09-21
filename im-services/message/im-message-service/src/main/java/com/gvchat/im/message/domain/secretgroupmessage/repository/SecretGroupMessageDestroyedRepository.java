package com.gvchat.im.message.domain.secretgroupmessage.repository;

import com.gvchat.im.message.domain.secretgroupmessage.model.SecretGroupMessageDestroyed;
import java.time.LocalDateTime;
import java.util.List;

public interface SecretGroupMessageDestroyedRepository {
  void save(SecretGroupMessageDestroyed destroyed);

  List<SecretGroupMessageDestroyed> listAfter(long secretGroupId, LocalDateTime afterDestroyAt, int limit);
}
