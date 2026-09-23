package io.openware.im.accessws.message;

import io.openware.im.accessws.service.WsBroadcastService;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.json.MqJsonCodec;
import io.openware.protocol.mq.event.ChatClearedEvent;
import io.openware.protocol.mq.group.ImMqConsumerGroups;
import io.openware.protocol.mq.topic.ImMqTopics;
import io.openware.protocol.ws.constant.WsEvents;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 会话记录被清空通知：发起方在服务端清空私聊/群聊后，通知其余成员清理端侧缓存与本地记录。
 *
 * <p>此前 {@code WsEvents.CHAT_CLEAR_PRIVATE_NOTIFY / CHAT_CLEAR_GROUP_NOTIFY} 只有常量声明、
 * 没有任何发布方，导致「同时删除服务端」后对方本地仍显示旧消息（服务端已删、对方还看得到）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatClearedEventListener implements SmartLifecycle {
  private final MqConsumerFactory mqConsumerFactory;
  private final MqJsonCodec mqJsonCodec;
  private final WsBroadcastService wsBroadcastService;
  private final EventDeduplicator eventDeduplicator;
  private volatile AutoCloseable consumer;
  private volatile boolean running;

  @Override
  public void start() {
    if (running) return;
    try {
      consumer = mqConsumerFactory.createOrderedConsumer(ImMqTopics.MESSAGE_CHAT_CLEARED_EVENT,
          ImMqConsumerGroups.ACCESS_WS_CHAT_CLEARED_NOTIFICATION,
          body -> deliver(mqJsonCodec.fromBytes(body, ChatClearedEvent.class)));
      running = true;
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to start chat cleared event listener", exception);
    }
  }

  @Override
  public void stop() {
    running = false;
    if (consumer == null) return;
    try {
      consumer.close();
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to stop chat cleared event listener", exception);
    } finally {
      consumer = null;
    }
  }

  @Override public void stop(Runnable callback) { stop(); callback.run(); }
  @Override public boolean isRunning() { return running; }
  @Override public boolean isAutoStartup() { return true; }
  @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
  @PreDestroy void onDestroy() { stop(); }

  private void deliver(ChatClearedEvent event) {
    log.info("收到会话清空事件, eventId={}, chatType={}, conversationId={}, recipients={}, clearedBy={}",
        event.eventId(), event.chatType(), event.conversationId(), event.recipientUserIds(),
        event.clearedByUserId());
    if (!eventDeduplicator.firstDelivery(event.eventId())) {
      log.info("会话清空事件重复投递，跳过, eventId={}", event.eventId());
      return;
    }
    try {
      List<Long> recipients = event.recipientUserIds() == null ? List.of() : event.recipientUserIds().stream()
          .filter(java.util.Objects::nonNull).distinct().toList();
      if (recipients.isEmpty()) {
        // 接收方为空的边界情况（如对方账号已注销）：无需下发，直接确认。
        return;
      }
      Map<String, Object> payload = new HashMap<>();
      payload.put("eventId", event.eventId());
      payload.put("conversationId", event.conversationId());
      payload.put("chatType", event.chatType());
      // 客户端按 peerId（私聊）/ groupId（群聊）取会话标识，字段名必须与 App 一致，
      // 否则 App 解析不到会话 id 会直接忽略整条通知（清空后对方本地记录仍然存在）。
      if ("group".equals(event.chatType())) {
        payload.put("groupId", event.targetId());
      } else {
        payload.put("peerId", event.targetId());
      }
      payload.put("targetId", event.targetId());
      payload.put("clearedByUserId", event.clearedByUserId());
      payload.put("clearedAt", event.clearedAt() == null ? "" : event.clearedAt().toString());
      payload.put("reason", event.reason() == null ? "cleared" : event.reason());
      String wsEvent = "group".equals(event.chatType())
          ? WsEvents.CHAT_CLEAR_GROUP_NOTIFY
          : WsEvents.CHAT_CLEAR_PRIVATE_NOTIFY;
      for (Long userId : recipients) {
        if (!wsBroadcastService.sendToUser(userId, wsEvent, payload)) {
          throw new IllegalStateException("Unable to publish chat cleared notification");
        }
      }
    } catch (RuntimeException exception) {
      eventDeduplicator.release(event.eventId());
      throw exception;
    }
  }
}
