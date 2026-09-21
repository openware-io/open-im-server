package com.gvchat.im.accessws.message;

import com.gvchat.im.accessws.service.WsBroadcastService;
import com.gvchat.infrastructure.mq.MqConsumerFactory;
import com.gvchat.infrastructure.mq.json.MqJsonCodec;
import com.gvchat.protocol.mq.event.SecretMessageDestroyedEvent;
import com.gvchat.protocol.mq.group.ImMqConsumerGroups;
import com.gvchat.protocol.mq.topic.ImMqTopics;
import com.gvchat.protocol.ws.constant.WsEvents;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 私密消息销毁事件监听：服务端调度器销毁消息后，对**在线**参与方主动推送
 * WS 销毁事件（客户端立即移除本地，删除=同步删除动作）；离线参与方由
 * states 增量拉取兜底。销毁状态始终以服务端为准。
 */
@Component
@Slf4j
public class SecretMessageDestroyedEventListener implements SmartLifecycle {
  private final MqConsumerFactory mqConsumerFactory;
  private final MqJsonCodec mqJsonCodec;
  private final WsBroadcastService wsBroadcastService;

  private volatile AutoCloseable consumer;
  private volatile boolean running;

  public SecretMessageDestroyedEventListener(
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
          ImMqTopics.SECRET_MESSAGE_DESTROYED_EVENT,
          ImMqConsumerGroups.ACCESS_WS_SECRET_DESTROYED,
          body -> handle(mqJsonCodec.fromBytes(body, SecretMessageDestroyedEvent.class)));
      running = true;
      log.info(
          "Started secret message destroyed event listener, topic={}, consumerGroup={}",
          ImMqTopics.SECRET_MESSAGE_DESTROYED_EVENT,
          ImMqConsumerGroups.ACCESS_WS_SECRET_DESTROYED);
    } catch (Exception ex) {
      log.error("Failed to start secret message destroyed event listener.", ex);
      throw new IllegalStateException("Failed to start secret message destroyed event listener.", ex);
    }
  }

  @Override
  public void stop() {
    running = false;
    if (consumer != null) {
      try {
        consumer.close();
      } catch (Exception ex) {
        log.warn("Failed to close secret message destroyed event listener cleanly.", ex);
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

  private void handle(SecretMessageDestroyedEvent event) {
    log.info(
        "Consuming secret message destroyed event, eventId={}, secretChatId={}, msgCount={}",
        event.getEventId(), event.getSecretChatId(), event.getMsgIds() == null ? 0 : event.getMsgIds().size());
    Map<String, Object> payload = Map.of(
        "secretChatId", event.getSecretChatId(),
        "msgIds", event.getMsgIds() == null ? java.util.List.of() : event.getMsgIds(),
        "destroyedAt", event.getDestroyedAt() == null ? "" : event.getDestroyedAt().toString(),
        "reason", event.getReason() == null ? "destroyed" : event.getReason());
    // 直接推送给参与方：sendToUser 经 Redis 广播，仅当目标用户存在实际 WS 会话时才送达
    // （离线用户 resolveRoomSessions 为空，空发无害）。不能用 isUserOnline（presence TTL）
    // 判断——App 后台挂起时 presence 会过期，导致「在线却漏推销毁」。
    for (Long participantId : event.getParticipantIds() == null
        ? java.util.List.<Long>of() : event.getParticipantIds()) {
      if (participantId == null) {
        continue;
      }
      wsBroadcastService.sendToUser(participantId, WsEvents.CHAT_SECRET_DESTROYED, payload);
      log.info("Published secret message destroyed push, secretChatId={}, userId={}, msgCount={}",
          event.getSecretChatId(), participantId, event.getMsgIds() == null ? 0 : event.getMsgIds().size());
    }
  }
}
