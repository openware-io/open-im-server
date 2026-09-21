package com.gvchat.im.accessws.message;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.protocol.mq.event.MessageStoredEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class StoredMessageWsPayloadFactory {
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
  };

  private final ObjectMapper objectMapper;

  public StoredMessageWsPayloadFactory(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public Map<String, Object> create(MessageStoredEvent event, long syncSeq) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("msgId", event.getMsgId());
    payload.put("from", event.getSenderId());
    payload.put("fromUsername", event.getSenderUsername());
    payload.put("toId", event.getToId());
    payload.put("chatType", event.getChatType());
    payload.put("msgType", event.getMsgType());
    payload.put("content", event.getContent());
    payload.put("media", event.getMedia() == null ? List.of() : event.getMedia());
    payload.put("seq", event.getSeq());
    payload.put("syncSeq", syncSeq);
    payload.put("conversationId", event.getConversationId());
    payload.put("timestamp", event.getCreatedAt());
    if (event.getClientMsgId() != null) {
      payload.put("clientMsgId", event.getClientMsgId());
    }
    if (event.getReplyMsgId() != null) {
      payload.put("replyMsgId", event.getReplyMsgId());
    }
    if (event.getAtUsersJson() != null && !event.getAtUsersJson().isBlank()) {
      try {
        payload.put("atUsers", objectMapper.readValue(event.getAtUsersJson(), STRING_LIST));
      } catch (Exception ex) {
        log.warn("Failed to parse atUsers payload for websocket message, msgId={}", event.getMsgId(), ex);
        payload.put("atUsers", List.of());
      }
    }
    return payload;
  }
}
