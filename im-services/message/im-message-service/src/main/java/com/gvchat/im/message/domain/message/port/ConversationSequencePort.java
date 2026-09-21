package com.gvchat.im.message.domain.message.port;

public interface ConversationSequencePort {
  long next(String conversationId);
}
