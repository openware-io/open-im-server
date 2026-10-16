package io.openware.im.message.domain.message.repository;

public interface ConversationSequencePort {
  long next(String conversationId);
}
