package io.openware.im.accessws.message;

import io.openware.im.accessws.push.JPushMessageService;
import io.openware.im.accessws.service.WsBroadcastService;
import io.openware.im.accessws.session.DevicePresenceRegistry;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.json.MqJsonCodec;
import io.openware.protocol.mq.event.FriendAcceptedEvent;
import io.openware.protocol.mq.event.FriendRequestedEvent;
import io.openware.protocol.mq.group.ImMqConsumerGroups;
import io.openware.protocol.mq.topic.ImMqTopics;
import io.openware.protocol.ws.constant.WsEvents;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class FriendEventListener implements SmartLifecycle {
  private final MqConsumerFactory mqConsumerFactory;
  private final MqJsonCodec mqJsonCodec;
  private final WsBroadcastService wsBroadcastService;
  private final EventDeduplicator eventDeduplicator;
  private final DevicePresenceRegistry devicePresenceRegistry;
  private final JPushMessageService jPushMessageService;
  private volatile AutoCloseable requestedConsumer;
  private volatile AutoCloseable acceptedConsumer;
  private volatile boolean running;

  @Override
  public void start() {
    if (running) {
      return;
    }
    try {
      requestedConsumer = mqConsumerFactory.createOrderedConsumer(
          ImMqTopics.FRIEND_REQUESTED_EVENT,
          ImMqConsumerGroups.ACCESS_WS_FRIEND_NOTIFICATION,
          body -> deliverRequested(mqJsonCodec.fromBytes(body, FriendRequestedEvent.class)));
      acceptedConsumer = mqConsumerFactory.createOrderedConsumer(
          ImMqTopics.FRIEND_ACCEPTED_EVENT,
          ImMqConsumerGroups.ACCESS_WS_FRIEND_NOTIFICATION,
          body -> deliverAccepted(mqJsonCodec.fromBytes(body, FriendAcceptedEvent.class)));
      running = true;
      log.info("好友事件监听器已启动, requestedTopic={}, acceptedTopic={}, consumerGroup={}",
          ImMqTopics.FRIEND_REQUESTED_EVENT,
          ImMqTopics.FRIEND_ACCEPTED_EVENT,
          ImMqConsumerGroups.ACCESS_WS_FRIEND_NOTIFICATION);
    } catch (Exception exception) {
      closeConsumers();
      log.error("好友事件监听器启动失败", exception);
      throw new IllegalStateException("好友事件监听器启动失败", exception);
    }
  }

  @Override
  public void stop() {
    running = false;
    closeConsumers();
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

  private void deliverRequested(FriendRequestedEvent event) {
    if (!eventDeduplicator.firstDelivery(event.getEventId())) {
      log.info("已跳过重复好友申请事件, eventId={}", event.getEventId());
      return;
    }
    try {
      if (!wsBroadcastService.sendToUser(event.getToUserId(), WsEvents.FRIEND_REQUEST_NOTIFY, Map.of(
          "request_id", event.getRequestId(),
          "from_user_id", event.getFromUserId(),
          "message", event.getMessage(),
          "event_id", event.getEventId()))) {
        throw new IllegalStateException("Failed to publish friend requested notification.");
      }
    } catch (RuntimeException exception) {
      eventDeduplicator.release(event.getEventId());
      throw exception;
    }
    // 后台/离线时再发 JPush 到达提示；前台由 WS 信号触发，不重复推送。
    if (event.getToUserId() != null && !devicePresenceRegistry.isAppForeground(event.getToUserId())) {
      try {
        jPushMessageService.pushFriendRequestIfConfigured(
            event.getToUserId(), event.getRequestId(), event.getFromUserId());
      } catch (RuntimeException ex) {
        log.error("Failed to push friend request offline notification, requestId={}, userId={}",
            event.getRequestId(), event.getToUserId(), ex);
      }
    }
    log.info("好友申请事件已下行, eventId={}, requestId={}, toUserId={}", event.getEventId(), event.getRequestId(), event.getToUserId());
  }

  private void deliverAccepted(FriendAcceptedEvent event) {
    if (!eventDeduplicator.firstDelivery(event.getEventId())) {
      log.info("已跳过重复好友接受事件, eventId={}", event.getEventId());
      return;
    }
    long requesterId = event.getFromUserId();
    long accepterId = event.getToUserId();
    try {
      // 双方都要收到「已互为好友」提醒：申请方收到「对方同意」，接受方收到「已添加对方」。
      boolean requesterOnline = wsBroadcastService.sendToUser(requesterId, WsEvents.FRIEND_ACCEPT_NOTIFY, Map.of(
          "request_id", event.getRequestId(),
          "friend_id", accepterId,
          "event_id", event.getEventId()));
      boolean accepterOnline = wsBroadcastService.sendToUser(accepterId, WsEvents.FRIEND_ACCEPT_NOTIFY, Map.of(
          "request_id", event.getRequestId(),
          "friend_id", requesterId,
          "event_id", event.getEventId()));
      if (!requesterOnline || !accepterOnline) {
        throw new IllegalStateException("Failed to publish friend accepted notification.");
      }
    } catch (RuntimeException exception) {
      eventDeduplicator.release(event.getEventId());
      throw exception;
    }
    // 离线兜底：双方任一端不在前台，都发送接受结果，保证多端账号不会静默。
    pushAcceptedIfOffline(requesterId, accepterId, event);
    pushAcceptedIfOffline(accepterId, requesterId, event);
    log.info("好友接受事件已下行, eventId={}, requestId={}, requester={}, accepter={}",
        event.getEventId(), event.getRequestId(), requesterId, accepterId);
  }

  private void pushAcceptedIfOffline(long userId, long friendId, FriendAcceptedEvent event) {
    if (devicePresenceRegistry.isAppForeground(userId)) {
      return;
    }
    try {
      jPushMessageService.pushFriendAcceptedIfConfigured(userId, friendId);
    } catch (RuntimeException ex) {
      log.error("Failed to push friend accept offline notification, requestId={}, userId={}",
          event.getRequestId(), userId, ex);
    }
  }

  private void closeConsumers() {
    close(requestedConsumer);
    close(acceptedConsumer);
    requestedConsumer = null;
    acceptedConsumer = null;
  }

  private void close(AutoCloseable consumer) {
    if (consumer == null) {
      return;
    }
    try {
      consumer.close();
    } catch (Exception exception) {
      log.warn("关闭好友事件消费者失败", exception);
    }
  }
}
