package com.gvchat.im.accessws.message;

import com.gvchat.im.accessws.service.WsBroadcastService;
import com.gvchat.infrastructure.mq.MqConsumerFactory;
import com.gvchat.infrastructure.mq.json.MqJsonCodec;
import com.gvchat.protocol.mq.event.MessageRecalledEvent;
import com.gvchat.protocol.mq.group.ImMqConsumerGroups;
import com.gvchat.protocol.mq.topic.ImMqTopics;
import com.gvchat.protocol.ws.constant.WsEvents;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MessageRecallEventListener implements SmartLifecycle {
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
      consumer = mqConsumerFactory.createOrderedConsumer(ImMqTopics.MESSAGE_RECALLED_EVENT,
          ImMqConsumerGroups.ACCESS_WS_RECALL_NOTIFICATION,
          body -> deliver(mqJsonCodec.fromBytes(body, MessageRecalledEvent.class)));
      running = true;
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to start message recall event listener", exception);
    }
  }

  @Override
  public void stop() {
    running = false;
    if (consumer == null) return;
    try {
      consumer.close();
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to stop message recall event listener", exception);
    } finally {
      consumer = null;
    }
  }

  @Override public void stop(Runnable callback) { stop(); callback.run(); }
  @Override public boolean isRunning() { return running; }
  @Override public boolean isAutoStartup() { return true; }
  @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
  @PreDestroy void onDestroy() { stop(); }

  private void deliver(MessageRecalledEvent event) {
    if (!eventDeduplicator.firstDelivery(event.eventId())) return;
    try {
      List<Long> recipients = event.recipientUserIds() == null ? List.of() : event.recipientUserIds().stream()
          .filter(java.util.Objects::nonNull).distinct().toList();
      if (recipients.isEmpty()) throw new IllegalStateException("Recalled message event has no recipients");
      Map<String, Object> payload = new HashMap<>();
      payload.put("eventId", event.eventId());
      payload.put("msgId", event.msgId());
      payload.put("conversationId", event.conversationId());
      payload.put("chatType", event.chatType());
      payload.put("toId", event.toId());
      payload.put("recalledByUserId", event.recalledByUserId());
      payload.put("recalledAt", event.recalledAt().toString());
      payload.put("reason", event.reason() == null ? "recalled" : event.reason());
      // 撤回→客户端渲染墓碑；删除→客户端移除本地消息（服务端均已硬删除）。
      String wsEvent = "deleted".equals(event.reason()) ? WsEvents.CHAT_DELETE_NOTIFY : WsEvents.CHAT_RECALL_NOTIFY;
      for (Long userId : recipients) {
        if (!wsBroadcastService.sendToUser(userId, wsEvent, payload)) {
          throw new IllegalStateException("Unable to publish recalled message notification");
        }
      }
    } catch (RuntimeException exception) {
      eventDeduplicator.release(event.eventId());
      throw exception;
    }
  }
}
