package io.openware.im.message.domain.message.repository;

import io.openware.im.message.domain.message.model.MessageOutbox;
import java.util.List;

public interface MessageOutboxRepository {
  MessageOutbox save(MessageOutbox outbox);
  List<MessageOutbox> findPending(int batchSize);
}
