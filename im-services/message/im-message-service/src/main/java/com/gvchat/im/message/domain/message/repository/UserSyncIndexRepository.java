package com.gvchat.im.message.domain.message.repository;

import com.gvchat.im.message.domain.message.model.UserSyncIndex;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface UserSyncIndexRepository {
  long nextSequence(long userId);
  void saveAll(List<UserSyncIndex> indexes);
  List<UserSyncIndex> findAfter(long userId, long afterSyncSeq, int limit);
  List<Long> findRecipientUserIdsByMsgId(String msgId);
  Set<String> findOwnedMessageIds(long userId, Collection<String> msgIds);
  void deleteByMsgId(String msgId);
}
