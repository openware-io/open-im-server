package com.gvchat.im.message.messaging.secretdestroy;

import com.gvchat.infrastructure.mq.MqConsumerFactory;
import com.gvchat.infrastructure.mq.json.MqJsonCodec;
import com.gvchat.im.message.application.secretmessage.SecretMessageApplicationService;
import com.gvchat.protocol.mq.event.SecretMessageDestroyCommand;
import com.gvchat.protocol.mq.group.ImMqConsumerGroups;
import com.gvchat.protocol.mq.topic.ImMqTopics;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 私密消息延迟销毁命令消费者：接收方已读后发布的 MQ 延迟消息到期后在此消费，
 * 精确销毁对应密文（仅当仍 active 且 destroyAt 已到）。在线端由销毁事件主动推 WS，
 * 离线端重入时由 states 增量拉取删除；周期扫描作为兜底。
 */
@Component
@Slf4j
public class SecretDestroyDelayedConsumer implements SmartLifecycle {
  private final MqConsumerFactory mqConsumerFactory;
  private final MqJsonCodec mqJsonCodec;
  private final SecretMessageApplicationService secretMessageService;

  private volatile AutoCloseable consumer;
  private volatile boolean running;

  public SecretDestroyDelayedConsumer(
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
          ImMqTopics.SECRET_MESSAGE_DESTROY_COMMAND,
          ImMqConsumerGroups.MESSAGE_SERVICE_SECRET_DESTROY,
          body -> handle(mqJsonCodec.fromBytes(body, SecretMessageDestroyCommand.class)));
      running = true;
      log.info(
          "Started secret destroy delayed consumer, topic={}, consumerGroup={}",
          ImMqTopics.SECRET_MESSAGE_DESTROY_COMMAND,
          ImMqConsumerGroups.MESSAGE_SERVICE_SECRET_DESTROY);
    } catch (Exception ex) {
      log.error("Failed to start secret destroy delayed consumer.", ex);
      throw new IllegalStateException("Failed to start secret destroy delayed consumer.", ex);
    }
  }

  @Override
  public void stop() {
    running = false;
    if (consumer != null) {
      try {
        consumer.close();
      } catch (Exception ex) {
        log.warn("Failed to close secret destroy delayed consumer cleanly.", ex);
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
  public int getPhase() {
    return Integer.MAX_VALUE - 100;
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  private void handle(SecretMessageDestroyCommand command) {
    log.info(
        "Consuming secret destroy delayed command, commandId={}, secretChatId={}, msgCount={}",
        command.getCommandId(), command.getSecretChatId(),
        command.getMsgIds() == null ? 0 : command.getMsgIds().size());
    secretMessageService.destroyDelayed(
        command.getSecretChatId(), command.getMsgIds(), LocalDateTime.now(Clock.systemUTC()));
  }
}
