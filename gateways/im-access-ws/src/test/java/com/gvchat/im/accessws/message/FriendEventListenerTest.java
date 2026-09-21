package com.gvchat.im.accessws.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.im.accessws.broadcast.RedisClusterBroadcast;
import com.gvchat.im.accessws.service.WsBroadcastService;
import com.gvchat.im.accessws.session.DevicePresenceRegistry;
import com.gvchat.infrastructure.mq.MqConsumerFactory;
import com.gvchat.infrastructure.mq.MqMessageHandler;
import com.gvchat.infrastructure.mq.json.MqJsonCodec;
import com.gvchat.protocol.mq.group.ImMqConsumerGroups;
import com.gvchat.protocol.mq.topic.ImMqTopics;
import com.gvchat.protocol.ws.constant.WsEvents;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FriendEventListenerTest {
  @Test
  void shouldRegisterConsumersAndDeliverFriendEvents() throws Exception {
    RecordingMqConsumerFactory consumerFactory = new RecordingMqConsumerFactory();
    RecordingWsBroadcastService broadcastService = new RecordingWsBroadcastService();
    FriendEventListener listener = new FriendEventListener(
        consumerFactory,
        new MqJsonCodec(new ObjectMapper()),
        broadcastService,
        deduplicator(true),
        alwaysForegroundPresence(), null);

    listener.start();

    assertTrue(listener.isRunning());
    assertEquals(ImMqConsumerGroups.ACCESS_WS_FRIEND_NOTIFICATION,
        consumerFactory.consumerGroups.get(ImMqTopics.FRIEND_REQUESTED_EVENT));
    assertEquals(ImMqConsumerGroups.ACCESS_WS_FRIEND_NOTIFICATION,
        consumerFactory.consumerGroups.get(ImMqTopics.FRIEND_ACCEPTED_EVENT));

    consumerFactory.handlers.get(ImMqTopics.FRIEND_REQUESTED_EVENT).handle(
        "{\"eventId\":\"evt-requested\",\"requestId\":1001,\"fromUserId\":11,\"toUserId\":22,\"message\":\"hello\"}"
            .getBytes(StandardCharsets.UTF_8));
    assertEquals(22L, broadcastService.userId);
    assertEquals(WsEvents.FRIEND_REQUEST_NOTIFY, broadcastService.event);
    assertEquals(Map.of(
        "request_id", 1001L,
        "from_user_id", 11L,
        "message", "hello",
        "event_id", "evt-requested"), broadcastService.data);

    consumerFactory.handlers.get(ImMqTopics.FRIEND_ACCEPTED_EVENT).handle(
        "{\"eventId\":\"evt-accepted\",\"requestId\":1001,\"fromUserId\":11,\"toUserId\":22}"
            .getBytes(StandardCharsets.UTF_8));
    assertEquals(3, broadcastService.calls.size());
    assertEquals(11L, broadcastService.calls.get(1).userId());
    assertEquals(22L, broadcastService.calls.get(2).userId());
    assertEquals(WsEvents.FRIEND_ACCEPT_NOTIFY, broadcastService.calls.get(1).event());
    assertEquals(Map.of(
        "request_id", 1001L,
        "friend_id", 22L,
        "event_id", "evt-accepted"), broadcastService.calls.get(1).data());
    assertEquals(Map.of(
        "request_id", 1001L,
        "friend_id", 11L,
        "event_id", "evt-accepted"), broadcastService.calls.get(2).data());

    listener.stop();

    assertFalse(listener.isRunning());
    assertEquals(1, consumerFactory.consumers.get(ImMqTopics.FRIEND_REQUESTED_EVENT).closeCount);
    assertEquals(1, consumerFactory.consumers.get(ImMqTopics.FRIEND_ACCEPTED_EVENT).closeCount);
  }

  @Test
  void shouldCloseFirstConsumerWhenSecondConsumerCannotStart() {
    RecordingMqConsumerFactory consumerFactory = new RecordingMqConsumerFactory();
    consumerFactory.failureTopic = ImMqTopics.FRIEND_ACCEPTED_EVENT;
    FriendEventListener listener = new FriendEventListener(
        consumerFactory,
        new MqJsonCodec(new ObjectMapper()),
        new RecordingWsBroadcastService(),
        deduplicator(true),
        alwaysForegroundPresence(), null);

    assertThrows(IllegalStateException.class, listener::start);

    assertFalse(listener.isRunning());
    assertEquals(1, consumerFactory.consumers.get(ImMqTopics.FRIEND_REQUESTED_EVENT).closeCount);
  }

  @Test
  void shouldSkipDuplicateFriendEvent() throws Exception {
    RecordingMqConsumerFactory consumerFactory = new RecordingMqConsumerFactory();
    RecordingWsBroadcastService broadcastService = new RecordingWsBroadcastService();
    FriendEventListener listener = new FriendEventListener(consumerFactory, new MqJsonCodec(new ObjectMapper()),
        broadcastService, deduplicator(false), alwaysForegroundPresence(), null);

    listener.start();
    consumerFactory.handlers.get(ImMqTopics.FRIEND_REQUESTED_EVENT).handle(
        "{\"eventId\":\"evt-requested\",\"requestId\":1001,\"fromUserId\":11,\"toUserId\":22,\"message\":\"hello\"}"
            .getBytes(StandardCharsets.UTF_8));

    assertEquals(0L, broadcastService.userId);
  }

  @Test
  void shouldReleaseDeduplicationReservationAndPropagateFailureWhenFriendDeliveryCannotBePublished()
      throws Exception {
    RecordingMqConsumerFactory consumerFactory = new RecordingMqConsumerFactory();
    RecordingEventDeduplicator deduplicator = new RecordingEventDeduplicator();
    FriendEventListener listener = new FriendEventListener(consumerFactory, new MqJsonCodec(new ObjectMapper()),
        new FailingWsBroadcastService(), deduplicator, alwaysForegroundPresence(), null);

    listener.start();

    assertThrows(IllegalStateException.class, () -> consumerFactory.handlers.get(ImMqTopics.FRIEND_REQUESTED_EVENT)
        .handle("{\"eventId\":\"evt-retry\",\"requestId\":1001,\"fromUserId\":11,\"toUserId\":22,\"message\":\"hello\"}"
            .getBytes(StandardCharsets.UTF_8)));

    assertEquals("evt-retry", deduplicator.releasedEventId);
  }

  private static final class RecordingMqConsumerFactory implements MqConsumerFactory {
    private final Map<String, MqMessageHandler> handlers = new HashMap<>();
    private final Map<String, String> consumerGroups = new HashMap<>();
    private final Map<String, RecordingConsumer> consumers = new HashMap<>();
    private String failureTopic;

    @Override
    public AutoCloseable createOrderedConsumer(String topic, String consumerGroup, MqMessageHandler handler)
        throws Exception {
      if (topic.equals(failureTopic)) {
        throw new IllegalStateException("consumer startup failure");
      }
      handlers.put(topic, handler);
      consumerGroups.put(topic, consumerGroup);
      RecordingConsumer consumer = new RecordingConsumer();
      consumers.put(topic, consumer);
      return consumer;
    }
  }

  private static EventDeduplicator deduplicator(boolean firstDelivery) {
    return new EventDeduplicator() {
      @Override
      public boolean firstDelivery(String eventId) {
        return firstDelivery;
      }

      @Override
      public void release(String eventId) {
      }
    };
  }

  /** 恒为「前台」的存在状态，使测试不触发 JPush 离线推送分支。 */
  private static DevicePresenceRegistry alwaysForegroundPresence() {
    return new DevicePresenceRegistry() {
      @Override
      public boolean isAppForeground(long userId) {
        return true;
      }
    };
  }

  private static final class RecordingEventDeduplicator implements EventDeduplicator {
    private String releasedEventId;

    @Override
    public boolean firstDelivery(String eventId) {
      return true;
    }

    @Override
    public void release(String eventId) {
      releasedEventId = eventId;
    }
  }

  private static final class RecordingConsumer implements AutoCloseable {
    private int closeCount;

    @Override
    public void close() {
      closeCount++;
    }
  }

  private static final class RecordingWsBroadcastService extends WsBroadcastService {
    private long userId;
    private String event;
    private Map<String, Object> data;
    private final java.util.List<Call> calls = new java.util.ArrayList<>();

    private RecordingWsBroadcastService() {
      super((RedisClusterBroadcast) null, null);
    }

    @Override
    public boolean sendToUser(long userId, String event, Map<String, Object> data) {
      this.userId = userId;
      this.event = event;
      this.data = data;
      calls.add(new Call(userId, event, data));
      return true;
    }

    private record Call(long userId, String event, Map<String, Object> data) {}
  }

  private static final class FailingWsBroadcastService extends WsBroadcastService {
    private FailingWsBroadcastService() {
      super((RedisClusterBroadcast) null, null);
    }

    @Override
    public boolean sendToUser(long userId, String event, Map<String, Object> data) {
      return false;
    }
  }
}
