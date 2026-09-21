package com.gvchat.im.accessws.message;

import com.gvchat.im.accessws.broadcast.RedisClusterBroadcast;
import com.gvchat.infrastructure.mq.MqConsumerFactory;
import com.gvchat.infrastructure.mq.json.MqJsonCodec;
import com.gvchat.protocol.mq.event.UserDataWipeRequestedEvent;
import com.gvchat.protocol.mq.group.ImMqConsumerGroups;
import com.gvchat.protocol.mq.topic.ImMqTopics;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 用户数据擦除请求事件监听器：通知各端 App 清理本地数据与缓存并关闭会话。 */
@Component
@Slf4j
@RequiredArgsConstructor
public class UserDataWipeRequestedEventListener implements SmartLifecycle {
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
          ImMqTopics.USER_DATA_WIPE_REQUESTED_EVENT,
          ImMqConsumerGroups.ACCESS_WS_DATA_WIPE,
          body -> wipe(mqJsonCodec.fromBytes(body, UserDataWipeRequestedEvent.class)));
      running = true;
      log.info("用户数据擦除请求事件监听器已启动, topic={}, consumerGroup={}",
          ImMqTopics.USER_DATA_WIPE_REQUESTED_EVENT, ImMqConsumerGroups.ACCESS_WS_DATA_WIPE);
    } catch (Exception exception) {
      closeConsumer();
      log.error("用户数据擦除请求事件监听器启动失败", exception);
      throw new IllegalStateException("用户数据擦除请求事件监听器启动失败", exception);
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
    return Integer.MAX_VALUE - 99;
  }

  private void wipe(UserDataWipeRequestedEvent event) {
    if (!eventDeduplicator.firstDelivery(event.eventId())) {
      log.info("已跳过重复用户数据擦除请求事件, eventId={}", event.eventId());
      return;
    }
    try {
      redisClusterBroadcast.publishDataWipe(event.userId());
      log.info("用户数据擦除请求事件已广播, eventId={}, userId={}", event.eventId(), event.userId());
    } catch (Exception exception) {
      eventDeduplicator.release(event.eventId());
      throw new IllegalStateException("用户数据擦除请求事件集群广播失败", exception);
    }
  }

  private void closeConsumer() {
    if (consumer == null) {
      return;
    }
    try {
      consumer.close();
    } catch (Exception exception) {
      log.warn("关闭用户数据擦除请求事件消费者失败", exception);
    } finally {
      consumer = null;
    }
  }
}
