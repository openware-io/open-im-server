package io.openware.im.accessws.message;

import lombok.extern.slf4j.Slf4j;

import io.openware.infrastructure.mq.MqProducer;
import io.openware.infrastructure.mq.json.MqJsonCodec;
import io.openware.protocol.mq.topic.ImMqTopics;
import io.openware.protocol.ws.dto.ReadReceiptDto;
import io.openware.protocol.ws.dto.RecallMessageDto;
import io.openware.protocol.ws.dto.SendMessageDto;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MqMessageGateway {
  private final MessageCommandFactory commandFactory;
  private final MessageStateCommandFactory stateCommandFactory;
  private final MqProducer mqProducer;
  private final MqJsonCodec mqJsonCodec;

  public MqMessageGateway(
      MessageCommandFactory commandFactory,
      MessageStateCommandFactory stateCommandFactory,
      MqProducer mqProducer,
      MqJsonCodec mqJsonCodec) {
    this.commandFactory = commandFactory;
    this.stateCommandFactory = stateCommandFactory;
    this.mqProducer = mqProducer;
    this.mqJsonCodec = mqJsonCodec;
  }

  public MessageAcceptance accept(Long senderId, String senderUsername, SendMessageDto dto) throws Exception {
    PreparedSendCommand prepared = commandFactory.create(senderId, senderUsername, dto);
    log.info(
        "Publishing message command to MQ, commandId={}, conversationId={}, senderId={}, chatType={}, toId={}, topic={}",
        prepared.command().getCommandId(),
        prepared.shardingKey(),
        senderId,
        dto.getChatType(),
        dto.getToId(),
        ImMqTopics.MESSAGE_SEND_COMMAND);
    mqProducer.sendOrdered(
        ImMqTopics.MESSAGE_SEND_COMMAND,
        prepared.shardingKey(),
        prepared.command().getCommandId(),
        mqJsonCodec.toBytes(prepared.command()));
    return prepared.acceptance();
  }

  public CommandAcceptance acceptRead(long userId, ReadReceiptDto dto) throws Exception {
    PreparedReadCommand prepared = stateCommandFactory.createRead(userId, dto.getMsgIds());
    mqProducer.sendOrdered(
        ImMqTopics.MESSAGE_READ_COMMAND,
        prepared.shardingKey(),
        prepared.command().getCommandId(),
        mqJsonCodec.toBytes(prepared.command()));
    return prepared.acceptance();
  }

  public CommandAcceptance acceptRecall(long userId, RecallMessageDto dto) throws Exception {
    PreparedRecallCommand prepared = stateCommandFactory.createRecall(userId, dto.getMsgId());
    mqProducer.sendOrdered(
        ImMqTopics.MESSAGE_RECALL_COMMAND,
        prepared.shardingKey(),
        prepared.command().getCommandId(),
        mqJsonCodec.toBytes(prepared.command()));
    return prepared.acceptance();
  }
}
