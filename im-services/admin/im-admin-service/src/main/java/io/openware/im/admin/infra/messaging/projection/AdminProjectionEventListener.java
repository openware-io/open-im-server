package io.openware.im.admin.infra.messaging.projection;

import io.openware.im.admin.application.projection.AdminReadProjectionPort;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.json.MqJsonCodec;
import io.openware.protocol.mq.event.MessageStoredEvent;
import io.openware.protocol.mq.event.UserStatusChangedEvent;
import io.openware.protocol.mq.group.ImMqConsumerGroups;
import io.openware.protocol.mq.topic.ImMqTopics;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class AdminProjectionEventListener implements SmartLifecycle {
  private final MqConsumerFactory mqConsumerFactory;
  private final MqJsonCodec mqJsonCodec;
  private final AdminReadProjectionPort projectionService;
  private final List<AutoCloseable> consumers = new ArrayList<>();
  private volatile boolean running;

  @Override
  public synchronized void start() {
    if (running) return;
    try {
      consumers.add(mqConsumerFactory.createOrderedConsumer(ImMqTopics.USER_STATUS_CHANGED_EVENT,
          ImMqConsumerGroups.ADMIN_PROJECTION_USER_STATUS, body -> projectionService.project(
              mqJsonCodec.fromBytes(body, UserStatusChangedEvent.class))));
      consumers.add(mqConsumerFactory.createOrderedConsumer(ImMqTopics.MESSAGE_STORED_EVENT,
          ImMqConsumerGroups.ADMIN_PROJECTION_MESSAGE, body -> projectionService.project(
              mqJsonCodec.fromBytes(body, MessageStoredEvent.class))));
      running = true;
    } catch (Exception exception) {
      stop();
      throw new IllegalStateException("Failed to start admin projection consumers", exception);
    }
  }

  @Override
  public synchronized void stop() {
    running = false;
    for (AutoCloseable consumer : consumers) {
      try {
        consumer.close();
      } catch (Exception exception) {
        log.warn("Failed to close admin projection consumer", exception);
      }
    }
    consumers.clear();
  }

  @Override
  public void stop(Runnable callback) {
    stop();
    callback.run();
  }

  @PreDestroy
  void destroy() {
    stop();
  }

  @Override
  public boolean isRunning() { return running; }

  @Override
  public boolean isAutoStartup() { return true; }
}
