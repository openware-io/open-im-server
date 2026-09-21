package com.gvchat.im.message.domain.message.repository;

import com.gvchat.im.message.domain.message.model.MessageOutbox;
import java.util.List;

public interface MessageOutboxRepository {
  MessageOutbox save(MessageOutbox outbox);
  List<MessageOutbox> findPending(int batchSize);
}
