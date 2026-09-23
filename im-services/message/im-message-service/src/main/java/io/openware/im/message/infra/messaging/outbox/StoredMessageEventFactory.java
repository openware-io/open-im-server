package io.openware.im.message.infra.messaging.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.im.message.domain.message.model.Message;
import io.openware.protocol.mq.event.MessageStoredEvent;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StoredMessageEventFactory {
  private final ObjectMapper objectMapper;

  public MessageStoredEvent from(Message message) {
    try {
      return MessageStoredEvent.builder().eventId(message.getMsgId())
          .conversationId(message.getConversationId()).seq(message.getSeq()).msgId(message.getMsgId())
          .senderId(message.getFromUserId()).senderUsername(message.getSenderUsername())
          .clientMsgId(message.getClientMsgId()).chatType(message.getChatType().getValue())
          .toId(message.getToId()).msgType(message.getMsgType().getValue()).content(message.getContent())
          .replyMsgId(message.getReplyMsgId()).atUsersJson(objectMapper.writeValueAsString(message.getAtUsers()))
          .createdAt(message.getCreatedAt().toInstant(ZoneOffset.UTC)).build();
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to map authoritative message to stored event.", ex);
    }
  }
}
