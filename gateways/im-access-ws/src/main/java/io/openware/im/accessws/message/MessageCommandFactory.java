package io.openware.im.accessws.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.enums.MsgType;
import io.openware.common.util.SnowflakeIdGenerator;
import io.openware.protocol.mq.command.MessageSendCommand;
import io.openware.protocol.mq.support.ConversationIds;
import io.openware.protocol.ws.dto.SendMessageDto;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class MessageCommandFactory {
  private final Supplier<String> messageIdSupplier;
  private final Clock clock;
  private final ObjectMapper objectMapper;

  @Autowired
  public MessageCommandFactory(ObjectMapper objectMapper, SnowflakeIdGenerator idGenerator) {
    this(idGenerator::nextId, Clock.systemUTC(), objectMapper);
  }

  MessageCommandFactory(Supplier<String> messageIdSupplier, Clock clock, ObjectMapper objectMapper) {
    this.messageIdSupplier = messageIdSupplier;
    this.clock = clock;
    this.objectMapper = objectMapper;
  }

  public PreparedSendCommand create(Long senderId, String senderUsername, SendMessageDto dto) throws Exception {
    // 系统消息仅由内部服务（如群聊邀请「XX 邀请 YY 加入群聊」）经 MQ 发出，用户经 WS 发送一律拒绝。
    if (dto.getMsgType() == MsgType.SYSTEM) {
      throw new IllegalArgumentException("System messages are internal only, not allowed via websocket.");
    }
    String msgId = messageIdSupplier.get();
    Instant acceptedAt = Instant.now(clock);
    String conversationId = buildConversationId(senderId, dto);

    MessageSendCommand command = MessageSendCommand.builder()
        .commandId(msgId)
        .conversationId(conversationId)
        .senderId(senderId)
        .senderUsername(senderUsername)
        .clientMsgId(dto.getClientMsgId())
        .chatType(dto.getChatType().getValue())
        .toId(dto.getToId())
        .msgType(dto.getMsgType().getValue())
        .content(dto.getContent())
        .replyMsgId(dto.getReplyMsgId())
        .atUsersJson(objectMapper.writeValueAsString(dto.getAtUsers() == null ? List.of() : dto.getAtUsers()))
        .mediaObjectIds(dto.getMediaObjectIds() == null ? List.of() : dto.getMediaObjectIds())
        .acceptedAt(acceptedAt)
        .build();

    return new PreparedSendCommand(command, new MessageAcceptance(msgId, acceptedAt), conversationId);
  }

  private String buildConversationId(Long senderId, SendMessageDto dto) {
    return switch (dto.getChatType()) {
      case PRIVATE -> ConversationIds.privateConversation(senderId, Long.parseLong(dto.getToId()));
      case GROUP -> ConversationIds.groupConversation(Long.parseLong(dto.getToId()));
      case CHANNEL -> ConversationIds.channelConversation(Long.parseLong(dto.getToId()));
      // 私密聊天消息仅允许走 HTTP /secret-messages 接口（E2EE 密文链路），禁止经 WS 发送。
      case SECRET -> throw new IllegalArgumentException("Secret messages must be sent via /secret-messages API, not websocket.");
      // 私密群聊消息仅允许走 HTTP /secret-group-messages 接口（E2EE 密文链路），禁止经 WS 发送。
      case SECRET_GROUP -> throw new IllegalArgumentException("Secret group messages must be sent via /secret-group-messages API, not websocket.");
    };
  }
}
