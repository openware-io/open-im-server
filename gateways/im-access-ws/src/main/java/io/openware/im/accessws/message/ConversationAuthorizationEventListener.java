package io.openware.im.accessws.message;

import io.openware.im.accessws.service.WsBroadcastService;
import io.openware.im.conversation.api.authorization.ConversationAuthorizationChangedEvent;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.json.MqJsonCodec;
import io.openware.protocol.mq.group.ImMqConsumerGroups;
import io.openware.protocol.mq.topic.ImMqTopics;
import io.openware.protocol.ws.constant.WsEvents;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConversationAuthorizationEventListener implements SmartLifecycle {
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
      consumer = mqConsumerFactory.createOrderedConsumer(ImMqTopics.CONVERSATION_AUTHORIZATION_CHANGED_EVENT,
          ImMqConsumerGroups.ACCESS_WS_CONVERSATION_NOTIFICATION,
          body -> deliver(mqJsonCodec.fromBytes(body, ConversationAuthorizationChangedEvent.class)));
      running = true;
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to start conversation authorization event listener", exception);
    }
  }

  @Override
  public void stop() {
    running = false;
    if (consumer == null) return;
    try {
      consumer.close();
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to stop conversation authorization event listener", exception);
    } finally {
      consumer = null;
    }
  }

  @Override public void stop(Runnable callback) { stop(); callback.run(); }
  @Override public boolean isRunning() { return running; }
  @Override public boolean isAutoStartup() { return true; }
  @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
  @PreDestroy void onDestroy() { stop(); }

  private void deliver(ConversationAuthorizationChangedEvent event) {
    if (!eventDeduplicator.firstDelivery(event.eventId())) return;
    try {
      Map<String, Object> payload = new HashMap<>();
      payload.put("eventId", event.eventId());
      payload.put("groupId", event.conversationId());
      payload.put("affectedUserId", event.affectedUserId());
      payload.put("changeType", event.changeType());
      payload.put("groupStatus", event.groupStatus());
      payload.put("memberStatus", event.memberStatus());
      payload.put("memberRole", event.memberRole());
      payload.put("authorizationVersion", event.authorizationVersion());
      if (event.mutedUntil() != null) payload.put("mutedUntil", event.mutedUntil().toString());
      if (!wsBroadcastService.sendToUser(event.userId(), WsEvents.GROUP_NOTIFY, payload)) {
        throw new IllegalStateException("Unable to publish group notification");
      }
    } catch (RuntimeException exception) {
      eventDeduplicator.release(event.eventId());
      throw exception;
    }
  }
}
