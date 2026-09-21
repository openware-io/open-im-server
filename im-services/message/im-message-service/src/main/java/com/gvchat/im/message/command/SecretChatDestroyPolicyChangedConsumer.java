package com.gvchat.im.message.command;

import com.gvchat.infrastructure.mq.MqConsumerFactory;
import com.gvchat.infrastructure.mq.json.MqJsonCodec;
import com.gvchat.im.message.application.secretmessage.SecretMessageApplicationService;
import com.gvchat.protocol.mq.event.SecretChatDestroyPolicyChangedEvent;
import com.gvchat.protocol.mq.group.ImMqConsumerGroups;
import com.gvchat.protocol.mq.topic.ImMqTopics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 私密聊天销毁策略变更事件消费者：对历史消息回补销毁计时（已到期立即销毁、未到期补计时）。 */
@Component
@Slf4j
public class SecretChatDestroyPolicyChangedConsumer implements SmartLifecycle {
  private final MqConsumerFactory mqConsumerFactory;
  private final MqJsonCodec mqJsonCodec;
  private final SecretMessageApplicationService secretMessageService;

  private volatile AutoCloseable consumer;
  private volatile boolean running;

  public SecretChatDestroyPolicyChangedConsumer(
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
          ImMqTopics.SECRET_CHAT_DESTROY_POLICY_CHANGED_EVENT,
          ImMqConsumerGroups.MESSAGE_SERVICE_SECRET_CHAT_DESTROY_POLICY_CHANGED,
          body -> {
            SecretChatDestroyPolicyChangedEvent event =
                mqJsonCodec.fromBytes(body, SecretChatDestroyPolicyChangedEvent.class);
            if (event.getSecretChatId() > 0) {
              secretMessageService.applyDestroyPolicy(event.getSecretChatId(), event.getPolicy());
            }
          });
      running = true;
      log.info("Started secret chat destroy policy changed consumer, topic={}, consumerGroup={}",
          ImMqTopics.SECRET_CHAT_DESTROY_POLICY_CHANGED_EVENT,
          ImMqConsumerGroups.MESSAGE_SERVICE_SECRET_CHAT_DESTROY_POLICY_CHANGED);
    } catch (Exception ex) {
      log.error("Failed to start secret chat destroy policy changed consumer.", ex);
      throw new IllegalStateException("Failed to start secret chat destroy policy changed consumer.", ex);
    }
  }

  @Override
  public void stop() {
    running = false;
    if (consumer != null) {
      try {
        consumer.close();
      } catch (Exception ex) {
        log.warn("Failed to close secret chat destroy policy changed consumer cleanly.", ex);
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
    return Integer.MAX_VALUE - 211;
  }
}
