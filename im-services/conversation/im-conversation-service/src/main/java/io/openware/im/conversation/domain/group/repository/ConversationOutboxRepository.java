package io.openware.im.conversation.domain.group.repository;

import java.util.List;

public interface ConversationOutboxRepository {
  void append(String eventId, String aggregateId, String topic, String shardingKey, String payloadJson);
  List<PendingEvent> findPending(int limit);
  void markPublished(long id);

  record PendingEvent(long id, String eventId, String aggregateId, String topic, String shardingKey, String payloadJson) {
  }
}
