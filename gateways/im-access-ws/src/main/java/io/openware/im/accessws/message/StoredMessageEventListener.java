package io.openware.im.accessws.message;

import io.openware.im.accessws.service.WsBroadcastService;
import io.openware.im.accessws.push.JPushMessageService;
import io.openware.im.accessws.session.DevicePresenceRegistry;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.json.MqJsonCodec;
import io.openware.protocol.mq.event.MessageStoredEvent;
import io.openware.protocol.mq.group.ImMqConsumerGroups;
import io.openware.protocol.mq.topic.ImMqTopics;
import io.openware.protocol.ws.constant.WsEvents;
import jakarta.annotation.PreDestroy;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class StoredMessageEventListener implements SmartLifecycle {
  private final MqConsumerFactory mqConsumerFactory;
  private final MqJsonCodec mqJsonCodec;
  private final StoredMessageWsPayloadFactory payloadFactory;
  private final WsBroadcastService wsBroadcastService;
  private final EventDeduplicator eventDeduplicator;
  private final DevicePresenceRegistry devicePresenceRegistry;
  private final JPushMessageService jPushMessageService;

  private volatile AutoCloseable consumer;
  private volatile boolean running;

  public StoredMessageEventListener(
      MqConsumerFactory mqConsumerFactory,
      MqJsonCodec mqJsonCodec,
      StoredMessageWsPayloadFactory payloadFactory,
      WsBroadcastService wsBroadcastService,
      EventDeduplicator eventDeduplicator,
      DevicePresenceRegistry devicePresenceRegistry,
      JPushMessageService jPushMessageService) {
    this.mqConsumerFactory = mqConsumerFactory;
    this.mqJsonCodec = mqJsonCodec;
    this.payloadFactory = payloadFactory;
    this.wsBroadcastService = wsBroadcastService;
    this.eventDeduplicator = eventDeduplicator;
    this.devicePresenceRegistry = devicePresenceRegistry;
    this.jPushMessageService = jPushMessageService;
  }

  @Override
  public void start() {
    if (running) {
      return;
    }
    try {
      consumer = mqConsumerFactory.createOrderedConsumer(
          ImMqTopics.MESSAGE_STORED_EVENT,
          ImMqConsumerGroups.ACCESS_WS_DELIVERY,
          body -> handle(mqJsonCodec.fromBytes(body, MessageStoredEvent.class)));
      running = true;
      log.info(
          "Started stored message event listener, topic={}, consumerGroup={}",
          ImMqTopics.MESSAGE_STORED_EVENT,
          ImMqConsumerGroups.ACCESS_WS_DELIVERY);
    } catch (Exception ex) {
      log.error("Failed to start stored message event listener.", ex);
      throw new IllegalStateException("Failed to start stored message event listener.", ex);
    }
  }

  @Override
  public void stop() {
    running = false;
    if (consumer != null) {
      try {
        consumer.close();
      } catch (Exception ex) {
        log.warn("Failed to close stored message event listener cleanly.", ex);
      } finally {
        consumer = null;
      }
    }
    log.info("Stopped stored message event listener.");
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
    return Integer.MAX_VALUE - 100;
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  private void handle(MessageStoredEvent event) {
    log.info(
        "Consuming stored message event for websocket delivery, eventId={}, msgId={}, conversationId={}, chatType={}",
        event.getEventId(),
        event.getMsgId(),
        event.getConversationId(),
        event.getChatType());
    if (!eventDeduplicator.firstDelivery(event.getEventId())) {
      log.info("Skipped duplicate stored message event, eventId={}", event.getEventId());
      return;
    }
    try {
      List<Long> recipientUserIds = event.getRecipientUserIds() == null ? List.of()
          : event.getRecipientUserIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
      if (recipientUserIds.isEmpty()) {
        // 无接收人的事件（例如发送方不在线且无其他接收方）无需投递；抛异常会让有序消费者
        // 无限重试并阻塞该队列后续所有消息投递，导致普通消息实时性受损。
        log.warn(
            "Skipping stored message event without recipients, eventId={}, msgId={}, conversationId={}, chatType={}",
            event.getEventId(),
            event.getMsgId(),
            event.getConversationId(),
            event.getChatType());
        return;
      }
      long recipientCount = 0;
      for (Long recipientUserId : recipientUserIds) {
        Long syncSeq = event.getRecipientSyncSeqs() == null ? null : event.getRecipientSyncSeqs().get(recipientUserId);
        if (syncSeq == null) {
          // 缺失同步序号时跳过该接收人（不做投递），避免抛异常导致有序消费者无限重试阻塞队列。
          log.warn(
              "Skipping recipient without sync sequence, eventId={}, msgId={}, recipientUserId={}",
              event.getEventId(),
              event.getMsgId(),
              recipientUserId);
          continue;
        }
        var payload = payloadFactory.create(event, syncSeq);
        if (!wsBroadcastService.sendToUser(recipientUserId, WsEvents.CHAT_RECEIVE, payload)) {
          throw new IllegalStateException("Failed to publish websocket message delivery.");
        }
        // 推送决策按「设备是否在前台」而非「WS 是否在线」：App 挂在后台但 Socket 仍连接时也要发极光。
        if (!devicePresenceRegistry.isAppForeground(recipientUserId)) {
          try {
            jPushMessageService.pushIfConfigured(recipientUserId, event);
          } catch (RuntimeException exception) {
            log.error("Failed to send JPush offline notification, msgId={}, userId={}",
                event.getMsgId(), recipientUserId, exception);
          }
        }
        recipientCount++;
      }
      log.info(
          "Delivered stored message event to recipients, msgId={}, senderId={}, recipientCount={}",
          event.getMsgId(),
          event.getSenderId(),
          recipientCount);
    } catch (RuntimeException ex) {
      eventDeduplicator.release(event.getEventId());
      throw ex;
    }
  }
}
