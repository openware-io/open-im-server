package io.openware.im.message.domain.message.repository;

import io.openware.im.message.domain.message.model.MessageReadStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;

public interface MessageReadStatusRepository {
  boolean saveIfAbsent(MessageReadStatus readStatus);
  Map<String, LocalDateTime> findReadAtByUserIdAndMsgIds(long userId, Collection<String> msgIds);
  void deleteByMsgId(String msgId);
}
