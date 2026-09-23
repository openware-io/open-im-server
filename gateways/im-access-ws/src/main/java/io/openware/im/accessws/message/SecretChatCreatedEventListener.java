package io.openware.im.accessws.message;

import io.openware.im.accessws.service.WsBroadcastService;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.json.MqJsonCodec;
import io.openware.protocol.mq.event.SecretChatCreatedEvent;
import io.openware.protocol.mq.group.ImMqConsumerGroups;
import io.openware.protocol.mq.topic.ImMqTopics;
import io.openware.protocol.ws.constant.WsEvents;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 私密聊天创建事件监听：对**对方**参与者推 WS 信号（仅元数据，绝不含密钥/内容），
 * 使其同步会话列表并完成 E2EE 握手，从而消除「对方首条消息滞后」。离线空发无害。
 */
@Component
@Slf4j
public class SecretChatCreatedEventListener implements SmartLifecycle {
  private final MqConsumerFactory mqConsumerFactory;
  private final MqJsonCodec mqJsonCodec;
  private final WsBroadcastService wsBroadcastService;

  private volatile AutoCloseable consumer;
  private volatile boolean running;

  public SecretChatCreatedEventListener(
      MqConsumerFactory mqConsumerFactory,
      MqJsonCodec mqJsonCodec,
      WsBroadcastService wsBroadcastService) {
    this.mqConsumerFactory = mqConsumerFactory;
    this.mqJsonCodec = mqJsonCodec;
    this.wsBroadcastService = wsBroadcastService;
  }

  @Override
  public void start() {
    if (running) {
      return;
    }
    try {
      consumer = mqConsumerFactory.createOrderedConsumer(
          ImMqTopics.SECRET_CHAT_CREATED_EVENT,
          ImMqConsumerGroups.ACCESS_WS_SECRET_CHAT_CREATED,
          body -> handle(mqJsonCodec.fromBytes(body, SecretChatCreatedEvent.class)));
      running = true;
      log.info("Started secret chat created event listener, topic={}, consumerGroup={}",
          ImMqTopics.SECRET_CHAT_CREATED_EVENT, ImMqConsumerGroups.ACCESS_WS_SECRET_CHAT_CREATED);
    } catch (Exception ex) {
      log.error("Failed to start secret chat created event listener.", ex);
      throw new IllegalStateException("Failed to start secret chat created event listener.", ex);
    }
  }

  @Override
  public void stop() {
    running = false;
    if (consumer != null) {
      try {
        consumer.close();
      } catch (Exception ex) {
        log.warn("Failed to close secret chat created event listener cleanly.", ex);
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

  private void handle(SecretChatCreatedEvent event) {
    log.info("Consuming secret chat created event, eventId={}, secretChatId={}, peerUserId={}",
        event.getEventId(), event.getSecretChatId(), event.getPeerUserId());
    Map<String, Object> payload = Map.of(
        "secretChatId", event.getSecretChatId(),
        "initiatorUserId", event.getInitiatorUserId());
    wsBroadcastService.sendToUser(event.getPeerUserId(), WsEvents.CHAT_SECRET_CREATED, payload);
    log.info("Published secret chat created push, secretChatId={}, userId={}",
        event.getSecretChatId(), event.getPeerUserId());
  }
}
