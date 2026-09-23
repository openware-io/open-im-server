package io.openware.im.message.domain.message.repository;

import java.time.LocalDateTime;
import java.util.Optional;

/** 好友通过自动消息的持久化去重记录。该记录独立于消息本体，消息删除后仍保留。 */
public interface FriendAcceptMessageDedupRepository {
  Optional<Record> findByRequestId(long requestId);

  boolean createPending(long requestId, String eventId, LocalDateTime createdAt);

  void mark(long requestId, String status, String msgId, LocalDateTime updatedAt);

  record Record(long requestId, String eventId, String status, String msgId) {
  }
}
