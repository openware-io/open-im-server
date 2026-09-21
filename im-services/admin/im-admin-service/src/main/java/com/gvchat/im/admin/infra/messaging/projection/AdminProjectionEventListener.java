package com.gvchat.im.admin.infra.messaging.projection;

import com.gvchat.im.admin.application.projection.AdminReadProjectionPort;
import com.gvchat.infrastructure.mq.MqConsumerFactory;
import com.gvchat.infrastructure.mq.json.MqJsonCodec;
import com.gvchat.protocol.mq.event.MessageStoredEvent;
import com.gvchat.protocol.mq.event.UserStatusChangedEvent;
import com.gvchat.protocol.mq.group.ImMqConsumerGroups;
import com.gvchat.protocol.mq.topic.ImMqTopics;
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
