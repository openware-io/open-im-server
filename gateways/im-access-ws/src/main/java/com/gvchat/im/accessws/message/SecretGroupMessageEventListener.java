package com.gvchat.im.accessws.message;

import com.gvchat.im.accessws.push.JPushMessageService;
import com.gvchat.im.accessws.service.WsBroadcastService;
import com.gvchat.im.accessws.session.DevicePresenceRegistry;
import com.gvchat.im.accessws.session.SessionRegistry;
import com.gvchat.infrastructure.mq.MqConsumerFactory;
import com.gvchat.infrastructure.mq.json.MqJsonCodec;
import com.gvchat.protocol.mq.event.SecretGroupMessageStoredEvent;
import com.gvchat.protocol.mq.group.ImMqConsumerGroups;
import com.gvchat.protocol.mq.topic.ImMqTopics;
import com.gvchat.protocol.ws.constant.WsEvents;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 私密群聊消息存储事件监听：对**每个在线接收方**推 WS 轻量信号（仅元数据，绝不含内容），
 * 客户端据此立即拉取属于自己的密文解密；对**后台**接收方（即使 Socket 在线）再发 JPush
 * 到达提示（「你收到一条加密群聊消息」，不含内容）。
 */
@Component
@Slf4j
public class SecretGroupMessageEventListener implements SmartLifecycle {
  private final MqConsumerFactory mqConsumerFactory;
  private final MqJsonCodec mqJsonCodec;
  private final WsBroadcastService wsBroadcastService;
  private final DevicePresenceRegistry devicePresenceRegistry;
  private final SessionRegistry sessionRegistry;
  private final JPushMessageService jPushMessageService;

  private volatile AutoCloseable consumer;
  private volatile boolean running;

  public SecretGroupMessageEventListener(
      MqConsumerFactory mqConsumerFactory,
      MqJsonCodec mqJsonCodec,
      WsBroadcastService wsBroadcastService,
      DevicePresenceRegistry devicePresenceRegistry,
      SessionRegistry sessionRegistry,
      JPushMessageService jPushMessageService) {
    this.mqConsumerFactory = mqConsumerFactory;
    this.mqJsonCodec = mqJsonCodec;
    this.wsBroadcastService = wsBroadcastService;
    this.devicePresenceRegistry = devicePresenceRegistry;
    this.sessionRegistry = sessionRegistry;
    this.jPushMessageService = jPushMessageService;
  }

  @Override
  public void start() {
    if (running) {
      return;
    }
    try {
      consumer = mqConsumerFactory.createOrderedConsumer(
          ImMqTopics.SECRET_GROUP_MESSAGE_STORED_EVENT,
          ImMqConsumerGroups.ACCESS_WS_SECRET_GROUP_NOTIFICATION,
          body -> handle(mqJsonCodec.fromBytes(body, SecretGroupMessageStoredEvent.class)));
      running = true;
      log.info(
          "Started secret group message event listener, topic={}, consumerGroup={}",
          ImMqTopics.SECRET_GROUP_MESSAGE_STORED_EVENT,
          ImMqConsumerGroups.ACCESS_WS_SECRET_GROUP_NOTIFICATION);
    } catch (Exception ex) {
      log.error("Failed to start secret group message event listener.", ex);
      throw new IllegalStateException("Failed to start secret group message event listener.", ex);
    }
  }

  @Override
  public void stop() {
    running = false;
    if (consumer != null) {
      try {
        consumer.close();
      } catch (Exception ex) {
        log.warn("Failed to close secret group message event listener cleanly.", ex);
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
    return Integer.MAX_VALUE - 110;
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  private void handle(SecretGroupMessageStoredEvent event) {
    log.info(
        "Consuming secret group message stored event, eventId={}, msgId={}, secretGroupId={}, senderId={}, recipients={}",
        event.getEventId(), event.getMsgId(), event.getSecretGroupId(), event.getSenderId(),
        event.getRecipientUserIds());
    if (event.getRecipientUserIds() == null) {
      return;
    }
    for (long recipientUserId : event.getRecipientUserIds()) {
      boolean mentioned = event.getAtUserIds() != null && event.getAtUserIds().contains(recipientUserId);
      // 在线接收方：推 WS 轻量信号（仅元数据，绝不含内容），客户端收到后立即拉取属于自己的密文解密。
      Map<String, Object> payload = Map.of(
          "secretGroupId", event.getSecretGroupId(),
          "senderId", event.getSenderId(),
          "msgId", event.getMsgId() == null ? "" : event.getMsgId(),
          "mentioned", mentioned);
      wsBroadcastService.sendToUser(recipientUserId, WsEvents.CHAT_SECRET_GROUP_STORED, payload);
      // 后台（即使 Socket 在线）或「前台但 WS 会话已断开」再发 JPush 到达提示，
      // 避免 App 前台但 Socket 掉线时消息既不走 WS 也不走 JPush（在线也收不到）。
      boolean online = sessionRegistry.isUserOnline(recipientUserId);
      if (!devicePresenceRegistry.isAppForeground(recipientUserId) || !online) {
        try {
          jPushMessageService.pushSecretGroupIfConfigured(
              recipientUserId, event.getSecretGroupId(), event.getSenderId(), event.getAtUserIds());
        } catch (RuntimeException ex) {
          log.error("Failed to push secret group message offline notification, msgId={}, userId={}",
              event.getMsgId(), recipientUserId, ex);
        }
      }
    }
  }
}
