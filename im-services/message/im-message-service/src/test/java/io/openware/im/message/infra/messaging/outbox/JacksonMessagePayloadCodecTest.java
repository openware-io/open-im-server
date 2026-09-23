package io.openware.im.message.infra.messaging.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.enums.ChatType;
import io.openware.common.enums.MsgType;
import io.openware.im.message.domain.message.model.Message;
import io.openware.protocol.mq.event.MessageStoredEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JacksonMessagePayloadCodecTest {
  @Test
  void shouldPreserveStoredMessageEventJsonContract() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    JacksonMessagePayloadCodec codec = new JacksonMessagePayloadCodec(objectMapper);
    Message message = Message.create("m-1", "private:7:9", 3L, 7L, "sender", "9", ChatType.PRIVATE,
        MsgType.TEXT, "hello", "client-1", "reply-1", List.of("9"), LocalDateTime.of(2026, 7, 22, 8, 30));

    String payloadJson = codec.writeStoredMessageEvent(message, List.of(9L), Map.of(9L, 1L), List.of());
    MessageStoredEvent event = objectMapper.readValue(payloadJson, MessageStoredEvent.class);

    assertEquals("m-1", event.getEventId());
    assertEquals("private:7:9", event.getConversationId());
    assertEquals(3L, event.getSeq());
    assertEquals("m-1", event.getMsgId());
    assertEquals(7L, event.getSenderId());
    assertEquals("sender", event.getSenderUsername());
    assertEquals("client-1", event.getClientMsgId());
    assertEquals("private", event.getChatType());
    assertEquals("9", event.getToId());
    assertEquals("text", event.getMsgType());
    assertEquals("hello", event.getContent());
    assertEquals("reply-1", event.getReplyMsgId());
    assertEquals("[\"9\"]", event.getAtUsersJson());
    assertEquals(List.of(9L), event.getRecipientUserIds());
    assertEquals(Map.of(9L, 1L), event.getRecipientSyncSeqs());
    assertEquals("2026-07-22T08:30:00Z", event.getCreatedAt().toString());
    assertEquals(List.of("9"), codec.readAtUsers("[\"9\"]"));
  }
}
