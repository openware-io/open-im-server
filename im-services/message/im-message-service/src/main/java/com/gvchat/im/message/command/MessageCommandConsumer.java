package com.gvchat.im.message.command;

import com.gvchat.infrastructure.mq.MqConsumerFactory;
import com.gvchat.infrastructure.mq.json.MqJsonCodec;
import com.gvchat.im.message.application.MessageApplicationService;
import com.gvchat.im.message.application.command.StoreMessageCommand;
import com.gvchat.protocol.mq.command.MessageSendCommand;
import com.gvchat.protocol.mq.group.ImMqConsumerGroups;
import com.gvchat.protocol.mq.topic.ImMqTopics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MessageCommandConsumer implements SmartLifecycle {
  private final MqConsumerFactory mqConsumerFactory;
  private final MqJsonCodec mqJsonCodec;
  private final MessageApplicationService messageApplicationService;

  private volatile AutoCloseable consumer;
  private volatile boolean running;

  public MessageCommandConsumer(
      MqConsumerFactory mqConsumerFactory,
      MqJsonCodec mqJsonCodec,
      MessageApplicationService messageApplicationService) {
    this.mqConsumerFactory = mqConsumerFactory;
    this.mqJsonCodec = mqJsonCodec;
    this.messageApplicationService = messageApplicationService;
  }

  @Override
  public void start() {
    if (running) {
      return;
    }
    try {
      consumer = mqConsumerFactory.createOrderedConsumer(
          ImMqTopics.MESSAGE_SEND_COMMAND,
          ImMqConsumerGroups.MESSAGE_SERVICE_WRITE_COMMAND,
          body -> {
            MessageSendCommand command = mqJsonCodec.fromBytes(body, MessageSendCommand.class);
            messageApplicationService.store(new StoreMessageCommand(command.getCommandId(),
                command.getConversationId(), command.getSenderId(), command.getSenderUsername(),
                command.getClientMsgId(), command.getChatType(), command.getToId(), command.getMsgType(),
                command.getContent(), command.getReplyMsgId(), command.getAtUsersJson(), command.getAcceptedAt(),
                command.getMediaObjectIds()));
          });
      running = true;
      log.info(
          "Started message command consumer, topic={}, consumerGroup={}",
          ImMqTopics.MESSAGE_SEND_COMMAND,
          ImMqConsumerGroups.MESSAGE_SERVICE_WRITE_COMMAND);
    } catch (Exception ex) {
      log.error("Failed to start message command consumer.", ex);
      throw new IllegalStateException("Failed to start message command consumer.", ex);
    }
  }

  @Override
  public void stop() {
    running = false;
    if (consumer != null) {
      try {
        consumer.close();
      } catch (Exception ex) {
        log.warn("Failed to close message command consumer cleanly.", ex);
      } finally {
        consumer = null;
      }
    }
    log.info("Stopped message command consumer.");
  }

  @Override
  public void stop(Runnable callback) {
    stop();
    callback.run();
  }

  @PreDestroy
  void onDestroy() {
    stop();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 200;
  }
}
