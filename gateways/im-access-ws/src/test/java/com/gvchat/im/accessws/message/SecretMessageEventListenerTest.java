package com.gvchat.im.accessws.message;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.im.accessws.broadcast.RedisClusterBroadcast;
import com.gvchat.im.accessws.push.JPushMessageService;
import com.gvchat.im.accessws.push.JPushProperties;
import com.gvchat.im.accessws.session.DevicePresenceRegistry;
import com.gvchat.im.accessws.session.SessionRegistry;
import com.gvchat.im.accessws.service.WsBroadcastService;
import com.gvchat.infrastructure.mq.MqConsumerFactory;
import com.gvchat.infrastructure.mq.MqMessageHandler;
import com.gvchat.infrastructure.mq.json.MqJsonCodec;
import com.gvchat.protocol.mq.topic.ImMqTopics;
import com.gvchat.protocol.ws.constant.WsEvents;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecretMessageEventListenerTest {
  @Test
  void shouldPushWsSignalToForegroundRecipientWithoutJpush() throws Exception {
    RecordingMqConsumerFactory consumerFactory = new RecordingMqConsumerFactory();
    RecordingWsBroadcastService broadcastService = new RecordingWsBroadcastService();
    RecordingSecretPushService pushService = new RecordingSecretPushService();
    DevicePresenceRegistry presence = new DevicePresenceRegistry();
    presence.report(9, "foreground", "");
    SecretMessageEventListener listener = new SecretMessageEventListener(consumerFactory,
        new MqJsonCodec(new ObjectMapper().findAndRegisterModules()), broadcastService, presence,
        new RecordingSessionRegistry(true), pushService);

    listener.start();
    consumerFactory.handlers.get(ImMqTopics.SECRET_MESSAGE_STORED_EVENT).handle(event("m-1", 42, 7, 9));

    assertEquals(List.of(9L), broadcastService.userIds);
    assertEquals(List.of(WsEvents.CHAT_SECRET_STORED), broadcastService.events);
    assertEquals(42L, broadcastService.payloads.get(0).get("secretChatId"));
    assertEquals(7L, broadcastService.payloads.get(0).get("senderId"));
    assertEquals("m-1", broadcastService.payloads.get(0).get("msgId"));
    assertEquals(0, pushService.invocations);
  }

  @Test
  void shouldPushWsSignalAndJpushToBackgroundRecipient() throws Exception {
    RecordingMqConsumerFactory consumerFactory = new RecordingMqConsumerFactory();
    RecordingWsBroadcastService broadcastService = new RecordingWsBroadcastService();
    RecordingSecretPushService pushService = new RecordingSecretPushService();
    DevicePresenceRegistry presence = new DevicePresenceRegistry();
    presence.report(9, "background", "");
    SecretMessageEventListener listener = new SecretMessageEventListener(consumerFactory,
        new MqJsonCodec(new ObjectMapper().findAndRegisterModules()), broadcastService, presence,
        new RecordingSessionRegistry(true), pushService);

    listener.start();
    consumerFactory.handlers.get(ImMqTopics.SECRET_MESSAGE_STORED_EVENT).handle(event("m-2", 43, 7, 9));

    assertEquals(List.of(9L), broadcastService.userIds);
    assertEquals(List.of(WsEvents.CHAT_SECRET_STORED), broadcastService.events);
    assertEquals(1, pushService.invocations);
  }

  @Test
  void shouldPushJpushToForegroundButDisconnectedRecipient() throws Exception {
    RecordingMqConsumerFactory consumerFactory = new RecordingMqConsumerFactory();
    RecordingWsBroadcastService broadcastService = new RecordingWsBroadcastService();
    RecordingSecretPushService pushService = new RecordingSecretPushService();
    DevicePresenceRegistry presence = new DevicePresenceRegistry();
    presence.report(9, "foreground", "");
    SecretMessageEventListener listener = new SecretMessageEventListener(consumerFactory,
        new MqJsonCodec(new ObjectMapper().findAndRegisterModules()), broadcastService, presence,
        new RecordingSessionRegistry(false), pushService);

    listener.start();
    consumerFactory.handlers.get(ImMqTopics.SECRET_MESSAGE_STORED_EVENT).handle(event("m-3", 44, 7, 9));

    assertEquals(List.of(9L), broadcastService.userIds);
    assertEquals(List.of(WsEvents.CHAT_SECRET_STORED), broadcastService.events);
    assertEquals(1, pushService.invocations);
  }

  private static byte[] event(String msgId, long secretChatId, long senderId, long recipientUserId) {
    return ("{\"eventId\":\"e-1\",\"msgId\":\"" + msgId + "\",\"secretChatId\":" + secretChatId
        + ",\"senderId\":" + senderId + ",\"recipientUserId\":" + recipientUserId + "}")
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

  private static final class RecordingSecretPushService extends JPushMessageService {
    private int invocations;

    private RecordingSecretPushService() {
      super(new JPushProperties(), null, null, null);
    }

    @Override
    public void pushSecretIfConfigured(long userId, long secretChatId, long senderId) {
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
