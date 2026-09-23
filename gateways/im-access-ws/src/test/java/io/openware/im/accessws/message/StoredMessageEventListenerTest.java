package io.openware.im.accessws.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.im.accessws.broadcast.RedisClusterBroadcast;
import io.openware.im.accessws.push.JPushMessageService;
import io.openware.im.accessws.push.JPushProperties;
import io.openware.im.accessws.session.DevicePresenceRegistry;
import io.openware.im.accessws.service.WsBroadcastService;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.MqMessageHandler;
import io.openware.infrastructure.mq.json.MqJsonCodec;
import io.openware.protocol.mq.topic.ImMqTopics;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StoredMessageEventListenerTest {
  @Test
  void shouldSkipDuplicateStoredMessageEventBeforeBroadcasting() throws Exception {
    RecordingMqConsumerFactory consumerFactory = new RecordingMqConsumerFactory();
    RecordingWsBroadcastService broadcastService = new RecordingWsBroadcastService();
    StoredMessageEventListener listener = new StoredMessageEventListener(consumerFactory,
        new MqJsonCodec(new ObjectMapper().findAndRegisterModules()),
        new StoredMessageWsPayloadFactory(new ObjectMapper()), broadcastService, deduplicator(false),
        new DevicePresenceRegistry(), disabledPushService());

    listener.start();
    consumerFactory.handlers.get(ImMqTopics.MESSAGE_STORED_EVENT).handle(
        "{\"eventId\":\"event-1\",\"msgId\":\"m-1\",\"conversationId\":\"private:7:9\",\"chatType\":\"private\",\"toId\":\"9\"}"
            .getBytes(StandardCharsets.UTF_8));

    assertEquals(0, broadcastService.userDeliveryCount);
  }

  @Test
  void shouldDeliverStoredMessageOnlyToEventRecipients() throws Exception {
    RecordingMqConsumerFactory consumerFactory = new RecordingMqConsumerFactory();
    RecordingWsBroadcastService broadcastService = new RecordingWsBroadcastService();
    StoredMessageEventListener listener = new StoredMessageEventListener(consumerFactory,
        new MqJsonCodec(new ObjectMapper().findAndRegisterModules()),
        new StoredMessageWsPayloadFactory(new ObjectMapper()), broadcastService, deduplicator(true),
        new DevicePresenceRegistry(), disabledPushService());

    listener.start();
    consumerFactory.handlers.get(ImMqTopics.MESSAGE_STORED_EVENT).handle(("{\"eventId\":\"event-1\",\"msgId\":\"m-1\","
        + "\"conversationId\":\"group:7\",\"chatType\":\"group\",\"toId\":\"7\","
        + "\"senderId\":\"1\",\"recipientUserIds\":[2,3,2],\"recipientSyncSeqs\":{\"2\":1,\"3\":2},\"createdAt\":\""
        + Instant.parse("2026-07-22T08:30:00Z") + "\"}").getBytes(StandardCharsets.UTF_8));

    assertEquals(List.of(2L, 3L), broadcastService.userIds);
  }

  @Test
  void shouldReleaseDeduplicationReservationAndPropagateFailureWhenDeliveryCannotBePublished()
      throws Exception {
    RecordingMqConsumerFactory consumerFactory = new RecordingMqConsumerFactory();
    RecordingEventDeduplicator deduplicator = new RecordingEventDeduplicator();
    StoredMessageEventListener listener = new StoredMessageEventListener(consumerFactory,
        new MqJsonCodec(new ObjectMapper().findAndRegisterModules()),
        new StoredMessageWsPayloadFactory(new ObjectMapper()), new FailingWsBroadcastService(), deduplicator,
        new DevicePresenceRegistry(), disabledPushService());

    listener.start();

    assertThrows(IllegalStateException.class, () -> consumerFactory.handlers.get(ImMqTopics.MESSAGE_STORED_EVENT)
        .handle(("{\"eventId\":\"event-retry\",\"msgId\":\"m-1\",\"conversationId\":\"private:7:9\","
            + "\"chatType\":\"private\",\"toId\":\"9\",\"recipientUserIds\":[9],\"recipientSyncSeqs\":{\"9\":1}}")
            .getBytes(StandardCharsets.UTF_8)));

    assertEquals("event-retry", deduplicator.releasedEventId);
  }

  @Test
  void shouldSkipEventWithoutRecipientsWithoutPropagatingFailure() throws Exception {
    RecordingMqConsumerFactory consumerFactory = new RecordingMqConsumerFactory();
    RecordingEventDeduplicator deduplicator = new RecordingEventDeduplicator();
    StoredMessageEventListener listener = new StoredMessageEventListener(consumerFactory,
        new MqJsonCodec(new ObjectMapper().findAndRegisterModules()),
        new StoredMessageWsPayloadFactory(new ObjectMapper()), new RecordingWsBroadcastService(), deduplicator,
        new DevicePresenceRegistry(), disabledPushService());

    listener.start();

    // 无接收人的事件必须静默跳过（不抛异常、不释放去重保留），避免有序消费者无限重试阻塞队列。
    assertDoesNotThrow(() -> consumerFactory.handlers.get(ImMqTopics.MESSAGE_STORED_EVENT)
        .handle(("{\"eventId\":\"event-without-recipients\",\"msgId\":\"m-1\","
            + "\"conversationId\":\"private:7:9\",\"chatType\":\"private\",\"toId\":\"9\","
            + "\"recipientUserIds\":[]}").getBytes(StandardCharsets.UTF_8)));

    assertEquals(null, deduplicator.releasedEventId);
  }

  @Test
  void shouldSkipRecipientWithoutSyncSequenceAndStillDeliverOthers() throws Exception {
    RecordingMqConsumerFactory consumerFactory = new RecordingMqConsumerFactory();
    RecordingEventDeduplicator deduplicator = new RecordingEventDeduplicator();
    RecordingWsBroadcastService broadcastService = new RecordingWsBroadcastService();
    StoredMessageEventListener listener = new StoredMessageEventListener(consumerFactory,
        new MqJsonCodec(new ObjectMapper().findAndRegisterModules()),
        new StoredMessageWsPayloadFactory(new ObjectMapper()), broadcastService, deduplicator,
        new DevicePresenceRegistry(), disabledPushService());

    listener.start();

    // 接收人 2 缺同步序号：跳过该接收人，其余接收人（3）仍正常投递，不抛异常。
    assertDoesNotThrow(() -> consumerFactory.handlers.get(ImMqTopics.MESSAGE_STORED_EVENT)
        .handle(("{\"eventId\":\"event-missing-seq\",\"msgId\":\"m-1\","
            + "\"conversationId\":\"group:7\",\"chatType\":\"group\",\"toId\":\"7\","
            + "\"recipientUserIds\":[2,3],\"recipientSyncSeqs\":{\"3\":2}}").getBytes(StandardCharsets.UTF_8)));

    assertEquals(List.of(3L), broadcastService.userIds);
    assertEquals(null, deduplicator.releasedEventId);
  }

  @Test
  void shouldReleaseDeduplicationReservationAndPropagateFailureWhenAnyRecipientDeliveryFails()
      throws Exception {
    RecordingMqConsumerFactory consumerFactory = new RecordingMqConsumerFactory();
    RecordingEventDeduplicator deduplicator = new RecordingEventDeduplicator();
    PartiallyFailingWsBroadcastService broadcastService = new PartiallyFailingWsBroadcastService();
    StoredMessageEventListener listener = new StoredMessageEventListener(consumerFactory,
        new MqJsonCodec(new ObjectMapper().findAndRegisterModules()),
        new StoredMessageWsPayloadFactory(new ObjectMapper()), broadcastService, deduplicator,
        new DevicePresenceRegistry(), disabledPushService());

    listener.start();

    assertThrows(IllegalStateException.class, () -> consumerFactory.handlers.get(ImMqTopics.MESSAGE_STORED_EVENT)
        .handle(("{\"eventId\":\"event-partial-failure\",\"msgId\":\"m-1\","
            + "\"conversationId\":\"group:7\",\"chatType\":\"group\",\"toId\":\"7\","
            + "\"recipientUserIds\":[2,3],\"recipientSyncSeqs\":{\"2\":1,\"3\":2}}").getBytes(StandardCharsets.UTF_8)));

    assertEquals(List.of(2L, 3L), broadcastService.userIds);
    assertEquals("event-partial-failure", deduplicator.releasedEventId);
  }

  @Test
  void shouldKeepMessageDeliverySuccessfulWhenOfflinePushFails() throws Exception {
    RecordingMqConsumerFactory consumerFactory = new RecordingMqConsumerFactory();
    RecordingWsBroadcastService broadcastService = new RecordingWsBroadcastService();
    FailingPushService pushService = new FailingPushService();
    StoredMessageEventListener listener = new StoredMessageEventListener(consumerFactory,
        new MqJsonCodec(new ObjectMapper().findAndRegisterModules()),
        new StoredMessageWsPayloadFactory(new ObjectMapper()), broadcastService, deduplicator(true),
        new DevicePresenceRegistry(), pushService);

    listener.start();

    assertDoesNotThrow(() -> consumerFactory.handlers.get(ImMqTopics.MESSAGE_STORED_EVENT)
        .handle(("{\"eventId\":\"event-offline-push-failure\",\"msgId\":\"m-1\","
            + "\"conversationId\":\"private:7:9\",\"chatType\":\"private\",\"toId\":\"9\","
            + "\"recipientUserIds\":[9],\"recipientSyncSeqs\":{\"9\":1}}")
            .getBytes(StandardCharsets.UTF_8)));

    assertEquals(1, broadcastService.userDeliveryCount);
    assertEquals(1, pushService.invocations);
  }

  private static final class RecordingMqConsumerFactory implements MqConsumerFactory {
    private final Map<String, MqMessageHandler> handlers = new HashMap<>();

    @Override
    public AutoCloseable createOrderedConsumer(String topic, String consumerGroup, MqMessageHandler handler) {
      handlers.put(topic, handler);
      return () -> { };
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

  private static JPushMessageService disabledPushService() {
    return new JPushMessageService(new JPushProperties(), null, null, null);
  }

  private static final class FailingPushService extends JPushMessageService {
    private int invocations;

    private FailingPushService() {
      super(new JPushProperties(), null, null, null);
    }

    @Override
    public void pushIfConfigured(long userId, io.openware.protocol.mq.event.MessageStoredEvent event) {
      invocations++;
      throw new IllegalStateException("JPush is unavailable");
    }
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

  private static final class RecordingWsBroadcastService extends WsBroadcastService {
    private int userDeliveryCount;
    private final List<Long> userIds = new ArrayList<>();

    private RecordingWsBroadcastService() {
      super((RedisClusterBroadcast) null, null);
    }

    @Override
    public boolean sendToUser(long userId, String event, Map<String, Object> data) {
      userDeliveryCount++;
      userIds.add(userId);
      return true;
    }
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

  private static final class PartiallyFailingWsBroadcastService extends WsBroadcastService {
    private final List<Long> userIds = new ArrayList<>();

    private PartiallyFailingWsBroadcastService() {
      super((RedisClusterBroadcast) null, null);
    }

    @Override
    public boolean sendToUser(long userId, String event, Map<String, Object> data) {
      userIds.add(userId);
      return userId != 3L;
    }
  }
}
