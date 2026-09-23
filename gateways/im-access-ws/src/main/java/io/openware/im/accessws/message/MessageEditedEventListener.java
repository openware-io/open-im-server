package io.openware.im.accessws.message;

import io.openware.im.accessws.service.WsBroadcastService;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.json.MqJsonCodec;
import io.openware.protocol.mq.event.MessageEditedEvent;
import io.openware.protocol.mq.group.ImMqConsumerGroups;
import io.openware.protocol.mq.topic.ImMqTopics;
import io.openware.protocol.ws.constant.WsEvents;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MessageEditedEventListener implements SmartLifecycle {
  private final MqConsumerFactory mqConsumerFactory;
  private final MqJsonCodec mqJsonCodec;
  private final WsBroadcastService wsBroadcastService;
  private final EventDeduplicator eventDeduplicator;
  private volatile AutoCloseable consumer;
  private volatile boolean running;

  @Override public void start() {
    if (running) return;
    try {
      consumer = mqConsumerFactory.createOrderedConsumer(ImMqTopics.MESSAGE_EDITED_EVENT,
          ImMqConsumerGroups.ACCESS_WS_EDIT_NOTIFICATION,
          body -> deliver(mqJsonCodec.fromBytes(body, MessageEditedEvent.class)));
      running = true;
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to start message edit event listener", exception);
    }
  }
  @Override public void stop() {
    running = false;
    if (consumer != null) try { consumer.close(); } catch (Exception ignored) {} finally { consumer = null; }
  }
  @Override public void stop(Runnable callback) { stop(); callback.run(); }
  @Override public boolean isRunning() { return running; }
  @Override public boolean isAutoStartup() { return true; }
  @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
  @PreDestroy void onDestroy() { stop(); }

  private void deliver(MessageEditedEvent event) {
    if (!eventDeduplicator.firstDelivery(event.eventId())) return;
    try {
      List<Long> recipients = event.recipientUserIds() == null ? List.of() : event.recipientUserIds().stream()
          .filter(java.util.Objects::nonNull).distinct().toList();
      Map<String, Object> payload = new HashMap<>();
      payload.put("eventId", event.eventId());
      payload.put("msgId", event.msgId());
      payload.put("conversationId", event.conversationId());
      payload.put("chatType", event.chatType());
      payload.put("toId", event.toId());
      payload.put("senderId", event.senderId());
      payload.put("seq", event.seq());
      payload.put("content", event.content());
      payload.put("edited", true);
      payload.put("editedAt", event.editedAt().toString());
      for (Long userId : recipients) {
        if (!wsBroadcastService.sendToUser(userId, WsEvents.CHAT_EDIT_NOTIFY, payload)) {
          throw new IllegalStateException("Unable to publish edited message notification");
        }
      }
    } catch (RuntimeException exception) {
      eventDeduplicator.release(event.eventId());
      throw exception;
    }
  }
}
