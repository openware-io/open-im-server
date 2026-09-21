package com.gvchat.im.message.infra.messaging.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.im.message.domain.message.event.MessageEditedEvent;
import com.gvchat.im.message.domain.message.model.Message;
import com.gvchat.im.message.domain.message.port.MessagePayloadCodecPort;
import com.gvchat.protocol.mq.event.ChatClearedEvent;
import com.gvchat.protocol.mq.event.MessageStoredEvent;
import com.gvchat.protocol.mq.event.MessageRecalledEvent;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import com.gvchat.protocol.mq.event.MessageMedia;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JacksonMessagePayloadCodec implements MessagePayloadCodecPort {
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

  private final ObjectMapper objectMapper;

  @Override
  public List<String> readAtUsers(String atUsersJson) {
    if (atUsersJson == null || atUsersJson.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(atUsersJson, STRING_LIST);
    } catch (Exception ex) {
      throw new IllegalArgumentException("Invalid atUsers payload.", ex);
    }
  }

  @Override
  public String writeStoredMessageEvent(Message message, List<Long> recipientUserIds, Map<Long, Long> recipientSyncSeqs,
      List<MessageMedia> media) {
    try {
      MessageStoredEvent event = MessageStoredEvent.builder().eventId(message.getMsgId())
          .conversationId(message.getConversationId()).seq(message.getSeq()).msgId(message.getMsgId())
          .senderId(message.getFromUserId()).senderUsername(message.getSenderUsername())
          .clientMsgId(message.getClientMsgId()).chatType(message.getChatType().getValue())
          .toId(message.getToId()).msgType(message.getMsgType().getValue()).content(message.getContent())
          .replyMsgId(message.getReplyMsgId()).atUsersJson(objectMapper.writeValueAsString(message.getAtUsers()))
          .media(media)
          .recipientUserIds(recipientUserIds)
          .recipientSyncSeqs(recipientSyncSeqs)
          .createdAt(message.getCreatedAt().toInstant(ZoneOffset.UTC)).build();
      return objectMapper.writeValueAsString(event);
    } catch (Exception ex) {
      throw new IllegalStateException("无法序列化已存储消息事件", ex);
    }
  }

  @Override
  public String writeMessageRecalledEvent(MessageRecalledEvent event) {
    try {
      return objectMapper.writeValueAsString(event);
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to serialize recalled message event", ex);
    }
  }

  @Override
  public String writeMessageEditedEvent(MessageEditedEvent event) {
    try {
      return objectMapper.writeValueAsString(event);
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to serialize edited message event", ex);
    }
  }

  @Override
  public String writeChatClearedEvent(ChatClearedEvent event) {
    try {
      return objectMapper.writeValueAsString(event);
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to serialize chat cleared event", ex);
    }
  }
}
