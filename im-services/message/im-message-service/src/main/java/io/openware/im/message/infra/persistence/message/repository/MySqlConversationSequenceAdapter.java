package io.openware.im.message.infra.persistence.message.repository;

import io.openware.im.message.domain.message.port.ConversationSequencePort;
import io.openware.im.message.infra.persistence.message.mapper.ConversationSequenceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MySqlConversationSequenceAdapter implements ConversationSequencePort {
  private final ConversationSequenceMapper mapper;

  @Override
  public long next(String conversationId) {
    mapper.initializeIfAbsent(conversationId);
    if (mapper.increment(conversationId) != 1) {
      throw new IllegalStateException("Unable to increment authoritative conversation sequence");
    }
    Long sequence = mapper.findLastSequence(conversationId);
    if (sequence == null) {
      throw new IllegalStateException("Authoritative conversation sequence disappeared");
    }
    return sequence;
  }
}
