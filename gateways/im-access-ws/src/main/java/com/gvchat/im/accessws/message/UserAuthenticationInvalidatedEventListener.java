package com.gvchat.im.accessws.message;

import com.gvchat.im.accessws.broadcast.RedisClusterBroadcast;
import com.gvchat.infrastructure.mq.MqConsumerFactory;
import com.gvchat.infrastructure.mq.json.MqJsonCodec;
import com.gvchat.protocol.mq.event.UserAuthenticationInvalidatedEvent;
import com.gvchat.protocol.mq.group.ImMqConsumerGroups;
import com.gvchat.protocol.mq.topic.ImMqTopics;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class UserAuthenticationInvalidatedEventListener implements SmartLifecycle {
  private final MqConsumerFactory mqConsumerFactory;
  private final MqJsonCodec mqJsonCodec;
  private final EventDeduplicator eventDeduplicator;
  private final RedisClusterBroadcast redisClusterBroadcast;
  private volatile AutoCloseable consumer;
  private volatile boolean running;

  @Override
  public void start() {
    if (running) {
      return;
    }
    try {
      consumer = mqConsumerFactory.createOrderedConsumer(
          ImMqTopics.USER_AUTHENTICATION_INVALIDATED_EVENT,
          ImMqConsumerGroups.ACCESS_WS_AUTHENTICATION_INVALIDATION,
          body -> invalidate(mqJsonCodec.fromBytes(body, UserAuthenticationInvalidatedEvent.class)));
      running = true;
      log.info("用户认证失效事件监听器已启动, topic={}, consumerGroup={}",
          ImMqTopics.USER_AUTHENTICATION_INVALIDATED_EVENT,
          ImMqConsumerGroups.ACCESS_WS_AUTHENTICATION_INVALIDATION);
    } catch (Exception exception) {
      closeConsumer();
      log.error("用户认证失效事件监听器启动失败", exception);
      throw new IllegalStateException("用户认证失效事件监听器启动失败", exception);
    }
  }

  @Override
  public void stop() {
    running = false;
    closeConsumer();
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
  public boolean isAutoStartup() {
    return true;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 100;
  }

  private void invalidate(UserAuthenticationInvalidatedEvent event) {
    if (!eventDeduplicator.firstDelivery(event.eventId())) {
      log.info("已跳过重复用户认证失效事件, eventId={}", event.eventId());
      return;
    }
    try {
      redisClusterBroadcast.publishAuthenticationInvalidation(event.userId());
      log.info("用户认证失效事件已广播, eventId={}, userId={}, authenticationVersion={}",
          event.eventId(), event.userId(), event.authenticationVersion());
    } catch (Exception exception) {
      eventDeduplicator.release(event.eventId());
      throw new IllegalStateException("用户认证失效事件集群广播失败", exception);
    }
  }

  private void closeConsumer() {
    if (consumer == null) {
      return;
    }
    try {
      consumer.close();
    } catch (Exception exception) {
      log.warn("关闭用户认证失效事件消费者失败", exception);
    } finally {
      consumer = null;
    }
  }
}
