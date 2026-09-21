package com.gvchat.im.accessws.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.common.enums.ChatType;
import com.gvchat.common.enums.MsgType;
import com.gvchat.protocol.ws.dto.SendMessageDto;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageCommandFactoryTest {
  @Test
  void shouldCreatePrivateMessageCommandWithNormalizedConversationId() throws Exception {
    MessageCommandFactory factory = new MessageCommandFactory(
        () -> "msg-1001",
        Clock.fixed(Instant.parse("2026-07-15T03:12:45Z"), ZoneOffset.UTC),
        new ObjectMapper());

    SendMessageDto dto = SendMessageDto.builder()
        .toId("7")
        .chatType(ChatType.PRIVATE)
        .msgType(MsgType.TEXT)
        .content("hello")
        .clientMsgId("client-1")
        .replyMsgId("reply-9")
        .atUsers(List.of("11", "12"))
        .build();

    PreparedSendCommand prepared = factory.create(9L, "alice", dto);

    assertEquals("conv:private:7:9", prepared.shardingKey());
    assertEquals("msg-1001", prepared.acceptance().msgId());
    assertEquals(Instant.parse("2026-07-15T03:12:45Z"), prepared.acceptance().acceptedAt());
    assertEquals("msg-1001", prepared.command().getCommandId());
    assertEquals("conv:private:7:9", prepared.command().getConversationId());
    assertEquals(9L, prepared.command().getSenderId());
    assertEquals("alice", prepared.command().getSenderUsername());
    assertEquals("client-1", prepared.command().getClientMsgId());
    assertEquals("private", prepared.command().getChatType());
    assertEquals("7", prepared.command().getToId());
    assertEquals("text", prepared.command().getMsgType());
    assertEquals("hello", prepared.command().getContent());
    assertEquals("reply-9", prepared.command().getReplyMsgId());
    assertEquals("[\"11\",\"12\"]", prepared.command().getAtUsersJson());
  }

  @Test
  void shouldCreateGroupMessageCommandWithGroupConversationId() throws Exception {
    MessageCommandFactory factory = new MessageCommandFactory(
        () -> "msg-2002",
        Clock.fixed(Instant.parse("2026-07-15T05:00:00Z"), ZoneOffset.UTC),
        new ObjectMapper());

    SendMessageDto dto = SendMessageDto.builder()
        .toId("88")
        .chatType(ChatType.GROUP)
        .msgType(MsgType.IMAGE)
        .content("image-key")
        .build();

    PreparedSendCommand prepared = factory.create(15L, "bob", dto);

    assertEquals("conv:group:88", prepared.shardingKey());
    assertEquals("conv:group:88", prepared.command().getConversationId());
    assertEquals("group", prepared.command().getChatType());
    assertEquals("image", prepared.command().getMsgType());
    assertEquals("[]", prepared.command().getAtUsersJson());
  }

  @Test
  void shouldRejectSecretChatTypeViaWebsocketSendPath() {
    MessageCommandFactory factory = new MessageCommandFactory(
        () -> "msg-3003",
        Clock.fixed(Instant.parse("2026-07-15T05:00:00Z"), ZoneOffset.UTC),
        new ObjectMapper());

    SendMessageDto dto = SendMessageDto.builder()
        .toId("55")
        .chatType(ChatType.SECRET)
        .msgType(MsgType.TEXT)
        .content("ciphertext")
        .build();

    // 私密聊天消息禁止走 WS chat:send（必须走 HTTP /secret-messages 密文链路）。
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> factory.create(15L, "bob", dto));
    assertEquals(
        "Secret messages must be sent via /secret-messages API, not websocket.",
        ex.getMessage());
  }

  @Test
  void shouldRejectSystemMessageViaWebsocketSendPath() {
    MessageCommandFactory factory = new MessageCommandFactory(
        () -> "msg-4004",
        Clock.fixed(Instant.parse("2026-07-15T05:00:00Z"), ZoneOffset.UTC),
        new ObjectMapper());

    SendMessageDto dto = SendMessageDto.builder()
        .toId("88")
        .chatType(ChatType.GROUP)
        .msgType(MsgType.SYSTEM)
        .content("fake system notice")
        .build();

    // 系统消息仅由内部服务经 MQ 发出，普通用户经 WS 伪造必须被拒绝。
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> factory.create(15L, "bob", dto));
    assertEquals(
        "System messages are internal only, not allowed via websocket.",
        ex.getMessage());
  }
}
