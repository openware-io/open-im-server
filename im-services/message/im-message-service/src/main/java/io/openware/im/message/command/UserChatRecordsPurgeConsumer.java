package io.openware.im.message.command;

import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.json.MqJsonCodec;
import io.openware.im.message.application.UserDataPurgeApplicationService;
import io.openware.protocol.mq.event.UserChatRecordsPurgeEvent;
import io.openware.protocol.mq.group.ImMqConsumerGroups;
import io.openware.protocol.mq.topic.ImMqTopics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 用户聊天记录清理事件消费者：清除该用户在消息服务的全部消息数据（含密文）。 */
@Component
@Slf4j
public class UserChatRecordsPurgeConsumer implements SmartLifecycle {
  private final MqConsumerFactory mqConsumerFactory;
  private final MqJsonCodec mqJsonCodec;
  private final UserDataPurgeApplicationService purgeService;

  private volatile AutoCloseable consumer;
  private volatile boolean running;

  public UserChatRecordsPurgeConsumer(
      MqConsumerFactory mqConsumerFactory,
      MqJsonCodec mqJsonCodec,
      UserDataPurgeApplicationService purgeService) {
    this.mqConsumerFactory = mqConsumerFactory;
    this.mqJsonCodec = mqJsonCodec;
    this.purgeService = purgeService;
  }

  @Override
  public void start() {
    if (running) {
      return;
    }
    try {
      consumer = mqConsumerFactory.createOrderedConsumer(
          ImMqTopics.USER_CHAT_RECORDS_PURGE_EVENT,
          ImMqConsumerGroups.MESSAGE_SERVICE_CHAT_RECORDS_PURGE,
          body -> {
            UserChatRecordsPurgeEvent event = mqJsonCodec.fromBytes(body, UserChatRecordsPurgeEvent.class);
            if (event.getUserId() != null && event.getUserId() > 0) {
              purgeService.purge(event.getUserId());
            }
          });
      running = true;
      log.info("Started user chat records purge consumer, topic={}, consumerGroup={}",
          ImMqTopics.USER_CHAT_RECORDS_PURGE_EVENT, ImMqConsumerGroups.MESSAGE_SERVICE_CHAT_RECORDS_PURGE);
    } catch (Exception ex) {
      log.error("Failed to start user chat records purge consumer.", ex);
      throw new IllegalStateException("Failed to start user chat records purge consumer.", ex);
    }
  }

  @Override
  public void stop() {
    running = false;
    if (consumer != null) {
      try {
        consumer.close();
      } catch (Exception ex) {
        log.warn("Failed to close user chat records purge consumer cleanly.", ex);
      } finally {
        consumer = null;
      }
    }
    log.info("Stopped user chat records purge consumer.");
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
