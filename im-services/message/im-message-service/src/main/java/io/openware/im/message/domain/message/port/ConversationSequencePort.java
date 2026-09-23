package io.openware.im.message.domain.message.port;

public interface ConversationSequencePort {
  long next(String conversationId);
}
