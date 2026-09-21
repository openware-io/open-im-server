package com.gvchat.im.message.command;

import com.gvchat.infrastructure.mq.MqConsumerFactory;
import com.gvchat.infrastructure.mq.json.MqJsonCodec;
import com.gvchat.im.message.application.MessageApplicationService;
import com.gvchat.im.message.application.command.DeleteMessageCommand;
import com.gvchat.protocol.mq.command.MessageRecallCommand;
import com.gvchat.protocol.mq.group.ImMqConsumerGroups;
import com.gvchat.protocol.mq.topic.ImMqTopics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MessageRecallCommandConsumer implements SmartLifecycle {
  private final MqConsumerFactory mqConsumerFactory;
  private final MqJsonCodec mqJsonCodec;
  private final MessageApplicationService messageApplicationService;

  private volatile AutoCloseable consumer;
  private volatile boolean running;

  public MessageRecallCommandConsumer(
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
          ImMqTopics.MESSAGE_RECALL_COMMAND,
          ImMqConsumerGroups.MESSAGE_SERVICE_RECALL_COMMAND,
          body -> {
            MessageRecallCommand command = mqJsonCodec.fromBytes(body, MessageRecallCommand.class);
            messageApplicationService.deleteForEveryone(new DeleteMessageCommand(command.getUserId(), command.getMsgId(), true));
          });
      running = true;
      log.info("Started message recall command consumer, topic={}, consumerGroup={}",
          ImMqTopics.MESSAGE_RECALL_COMMAND, ImMqConsumerGroups.MESSAGE_SERVICE_RECALL_COMMAND);
    } catch (Exception ex) {
      log.error("Failed to start message recall command consumer.", ex);
      throw new IllegalStateException("Failed to start message recall command consumer.", ex);
    }
  }

  @Override
  public void stop() {
    running = false;
    if (consumer != null) {
      try {
        consumer.close();
      } catch (Exception ex) {
        log.warn("Failed to close message recall command consumer cleanly.", ex);
      } finally {
        consumer = null;
      }
    }
    log.info("Stopped message recall command consumer.");
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
