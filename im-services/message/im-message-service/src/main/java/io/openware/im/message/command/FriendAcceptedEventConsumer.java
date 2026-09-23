package io.openware.im.message.command;

import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.json.MqJsonCodec;
import io.openware.im.message.application.FriendAcceptedMessageApplicationService;
import io.openware.protocol.mq.event.FriendAcceptedEvent;
import io.openware.protocol.mq.group.ImMqConsumerGroups;
import io.openware.protocol.mq.topic.ImMqTopics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 消费好友通过事件生成正式私聊消息；异常必须抛回 MQ 以触发重试/DLQ。 */
@Component
@Slf4j
public class FriendAcceptedEventConsumer implements SmartLifecycle {
  private final MqConsumerFactory mqConsumerFactory;
  private final MqJsonCodec mqJsonCodec;
  private final FriendAcceptedMessageApplicationService service;
  private volatile AutoCloseable consumer;
  private volatile boolean running;

  public FriendAcceptedEventConsumer(MqConsumerFactory mqConsumerFactory, MqJsonCodec mqJsonCodec,
      FriendAcceptedMessageApplicationService service) {
    this.mqConsumerFactory = mqConsumerFactory;
    this.mqJsonCodec = mqJsonCodec;
    this.service = service;
  }

  @Override
  public void start() {
    if (running) return;
    try {
      consumer = mqConsumerFactory.createOrderedConsumer(ImMqTopics.FRIEND_ACCEPTED_EVENT,
          ImMqConsumerGroups.MESSAGE_SERVICE_FRIEND_ACCEPTED,
          body -> service.handle(mqJsonCodec.fromBytes(body, FriendAcceptedEvent.class)));
      running = true;
      log.info("好友通过自动消息消费者已启动, topic={}, consumerGroup={}", ImMqTopics.FRIEND_ACCEPTED_EVENT,
          ImMqConsumerGroups.MESSAGE_SERVICE_FRIEND_ACCEPTED);
    } catch (Exception ex) {
      log.error("好友通过自动消息消费者启动失败", ex);
      throw new IllegalStateException("Failed to start friend accepted consumer", ex);
    }
  }

  @Override public void stop() { running = false; if (consumer != null) try { consumer.close(); } catch (Exception ex) {
    log.warn("好友通过自动消息消费者关闭失败", ex);
  } finally { consumer = null; } }
  @Override public void stop(Runnable callback) { stop(); callback.run(); }
  @PreDestroy void onDestroy() { stop(); }
  @Override public boolean isRunning() { return running; }
  @Override public boolean isAutoStartup() { return true; }
  @Override public int getPhase() { return Integer.MAX_VALUE - 210; }
}
