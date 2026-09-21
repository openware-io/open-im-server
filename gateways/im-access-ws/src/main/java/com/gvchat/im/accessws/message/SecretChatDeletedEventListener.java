package com.gvchat.im.accessws.message;

import com.gvchat.im.accessws.service.WsBroadcastService;
import com.gvchat.infrastructure.mq.MqConsumerFactory;
import com.gvchat.infrastructure.mq.json.MqJsonCodec;
import com.gvchat.protocol.mq.event.SecretChatDeletedEvent;
import com.gvchat.protocol.mq.group.ImMqConsumerGroups;
import com.gvchat.protocol.mq.topic.ImMqTopics;
import com.gvchat.protocol.ws.constant.WsEvents;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 私密聊天终止事件监听：对**对方**参与者推 WS 信号（仅元数据），
 * 使其移除会话并清空本地密文消息。离线空发无害（下次同步时服务端已无该会话）。
 */
@Component
@Slf4j
public class SecretChatDeletedEventListener implements SmartLifecycle {
  private final MqConsumerFactory mqConsumerFactory;
  private final MqJsonCodec mqJsonCodec;
  private final WsBroadcastService wsBroadcastService;

  private volatile AutoCloseable consumer;
  private volatile boolean running;

  public SecretChatDeletedEventListener(
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
          ImMqTopics.SECRET_CHAT_DELETED_EVENT,
          ImMqConsumerGroups.ACCESS_WS_SECRET_CHAT_DELETED,
          body -> handle(mqJsonCodec.fromBytes(body, SecretChatDeletedEvent.class)));
      running = true;
      log.info("Started secret chat deleted event listener, topic={}, consumerGroup={}",
          ImMqTopics.SECRET_CHAT_DELETED_EVENT, ImMqConsumerGroups.ACCESS_WS_SECRET_CHAT_DELETED);
    } catch (Exception ex) {
      log.error("Failed to start secret chat deleted event listener.", ex);
      throw new IllegalStateException("Failed to start secret chat deleted event listener.", ex);
    }
  }

  @Override
  public void stop() {
    running = false;
    if (consumer != null) {
      try {
        consumer.close();
      } catch (Exception ex) {
        log.warn("Failed to close secret chat deleted event listener cleanly.", ex);
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

  private void handle(SecretChatDeletedEvent event) {
    log.info("Consuming secret chat deleted event, eventId={}, secretChatId={}, peerUserId={}",
        event.getEventId(), event.getSecretChatId(), event.getPeerUserId());
    Map<String, Object> payload = Map.of(
        "secretChatId", event.getSecretChatId(),
        "initiatorUserId", event.getInitiatorUserId());
    wsBroadcastService.sendToUser(event.getPeerUserId(), WsEvents.CHAT_SECRET_DELETED, payload);
    log.info("Published secret chat deleted push, secretChatId={}, userId={}",
        event.getSecretChatId(), event.getPeerUserId());
  }
}
