package io.openware.im.accessws.message;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.im.accessws.broadcast.RedisClusterBroadcast;
import io.openware.im.accessws.push.JPushMessageService;
import io.openware.im.accessws.push.JPushProperties;
import io.openware.im.accessws.session.DevicePresenceRegistry;
import io.openware.im.accessws.session.SessionRegistry;
import io.openware.im.accessws.service.WsBroadcastService;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.MqMessageHandler;
import io.openware.infrastructure.mq.json.MqJsonCodec;
import io.openware.protocol.mq.topic.ImMqTopics;
import io.openware.protocol.ws.constant.WsEvents;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecretGroupMessageEventListenerTest {
  @Test
  void shouldPushWsSignalToForegroundRecipientsWithoutJpush() throws Exception {
    RecordingMqConsumerFactory consumerFactory = new RecordingMqConsumerFactory();
    RecordingWsBroadcastService broadcastService = new RecordingWsBroadcastService();
    RecordingSecretGroupPushService pushService = new RecordingSecretGroupPushService();
    DevicePresenceRegistry presence = new DevicePresenceRegistry();
    presence.report(9, "foreground", "");
    presence.report(10, "foreground", "");
    SecretGroupMessageEventListener listener = new SecretGroupMessageEventListener(consumerFactory,
        new MqJsonCodec(new ObjectMapper().findAndRegisterModules()), broadcastService, presence,
        new RecordingSessionRegistry(true), pushService);

    listener.start();
    consumerFactory.handlers.get(ImMqTopics.SECRET_GROUP_MESSAGE_STORED_EVENT).handle(event("m-1", 42, 7));

    assertEquals(List.of(9L, 10L), broadcastService.userIds);
    assertEquals(List.of(WsEvents.CHAT_SECRET_GROUP_STORED, WsEvents.CHAT_SECRET_GROUP_STORED),
        broadcastService.events);
    assertEquals(42L, broadcastService.payloads.get(0).get("secretGroupId"));
    assertEquals(7L, broadcastService.payloads.get(0).get("senderId"));
    assertEquals("m-1", broadcastService.payloads.get(0).get("msgId"));
    assertEquals(0, pushService.invocations);
  }

  @Test
  void shouldPushWsSignalAndJpushToBackgroundRecipients() throws Exception {
    RecordingMqConsumerFactory consumerFactory = new RecordingMqConsumerFactory();
    RecordingWsBroadcastService broadcastService = new RecordingWsBroadcastService();
    RecordingSecretGroupPushService pushService = new RecordingSecretGroupPushService();
    DevicePresenceRegistry presence = new DevicePresenceRegistry();
    presence.report(9, "background", "");
    presence.report(10, "background", "");
    SecretGroupMessageEventListener listener = new SecretGroupMessageEventListener(consumerFactory,
        new MqJsonCodec(new ObjectMapper().findAndRegisterModules()), broadcastService, presence,
        new RecordingSessionRegistry(true), pushService);

    listener.start();
    consumerFactory.handlers.get(ImMqTopics.SECRET_GROUP_MESSAGE_STORED_EVENT).handle(event("m-2", 43, 7));

    assertEquals(List.of(9L, 10L), broadcastService.userIds);
    assertEquals(2, pushService.invocations);
  }

  private static byte[] event(String msgId, long secretGroupId, long senderId) {
    return ("{\"eventId\":\"e-1\",\"msgId\":\"" + msgId + "\",\"secretGroupId\":" + secretGroupId
        + ",\"senderId\":" + senderId + ",\"recipientUserIds\":[9,10]}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static final class RecordingMqConsumerFactory implements MqConsumerFactory {
    private final Map<String, MqMessageHandler> handlers = new HashMap<>();

    @Override
    public AutoCloseable createOrderedConsumer(String topic, String consumerGroup, MqMessageHandler handler) {
      handlers.put(topic, handler);
      return () -> { };
    }
  }

  private static final class RecordingSecretGroupPushService extends JPushMessageService {
    private int invocations;

    private RecordingSecretGroupPushService() {
      super(new JPushProperties(), null, null, null);
    }

    @Override
    public void pushSecretGroupIfConfigured(long userId, long secretGroupId, long senderId, List<Long> atUserIds) {
      invocations++;
    }
  }

  private static final class RecordingSessionRegistry extends SessionRegistry {
    private final boolean online;

    private RecordingSessionRegistry(boolean online) {
      this.online = online;
    }

    @Override
    public boolean isUserOnline(Long userId) {
      return online;
    }
  }

  private static final class RecordingWsBroadcastService extends WsBroadcastService {
    private final List<Long> userIds = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final List<Map<String, Object>> payloads = new ArrayList<>();

    private RecordingWsBroadcastService() {
      super((RedisClusterBroadcast) null, null);
    }

    @Override
    public boolean sendToUser(long userId, String event, Map<String, Object> data) {
      userIds.add(userId);
      events.add(event);
      payloads.add(data);
      return true;
    }
  }
}
