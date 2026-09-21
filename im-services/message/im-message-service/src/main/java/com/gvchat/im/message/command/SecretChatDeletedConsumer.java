package com.gvchat.im.message.command;

import com.gvchat.infrastructure.mq.MqConsumerFactory;
import com.gvchat.infrastructure.mq.json.MqJsonCodec;
import com.gvchat.im.message.application.secretmessage.SecretMessageApplicationService;
import com.gvchat.protocol.mq.event.SecretChatDeletedEvent;
import com.gvchat.protocol.mq.group.ImMqConsumerGroups;
import com.gvchat.protocol.mq.topic.ImMqTopics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 私密聊天终止事件消费者：硬删除该会话全部密文（Telegram 语义：删除即销毁双方消息）。 */
@Component
@Slf4j
public class SecretChatDeletedConsumer implements SmartLifecycle {
  private final MqConsumerFactory mqConsumerFactory;
  private final MqJsonCodec mqJsonCodec;
  private final SecretMessageApplicationService secretMessageService;

  private volatile AutoCloseable consumer;
  private volatile boolean running;

  public SecretChatDeletedConsumer(
      MqConsumerFactory mqConsumerFactory,
      MqJsonCodec mqJsonCodec,
      SecretMessageApplicationService secretMessageService) {
    this.mqConsumerFactory = mqConsumerFactory;
    this.mqJsonCodec = mqJsonCodec;
    this.secretMessageService = secretMessageService;
  }

  @Override
  public void start() {
    if (running) {
      return;
    }
    try {
      consumer = mqConsumerFactory.createOrderedConsumer(
          ImMqTopics.SECRET_CHAT_DELETED_EVENT,
          ImMqConsumerGroups.MESSAGE_SERVICE_SECRET_CHAT_DELETED,
          body -> {
            SecretChatDeletedEvent event = mqJsonCodec.fromBytes(body, SecretChatDeletedEvent.class);
            if (event.getSecretChatId() > 0) {
              secretMessageService.purgeBySecretChatId(event.getSecretChatId());
            }
          });
      running = true;
      log.info("Started secret chat deleted consumer, topic={}, consumerGroup={}",
          ImMqTopics.SECRET_CHAT_DELETED_EVENT, ImMqConsumerGroups.MESSAGE_SERVICE_SECRET_CHAT_DELETED);
    } catch (Exception ex) {
      log.error("Failed to start secret chat deleted consumer.", ex);
      throw new IllegalStateException("Failed to start secret chat deleted consumer.", ex);
    }
  }

  @Override
  public void stop() {
    running = false;
    if (consumer != null) {
      try {
        consumer.close();
      } catch (Exception ex) {
        log.warn("Failed to close secret chat deleted consumer cleanly.", ex);
      } finally {
        consumer = null;
      }
    }
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
    return Integer.MAX_VALUE - 210;
  }
}
