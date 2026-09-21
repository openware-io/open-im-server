package com.gvchat.im.message.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.im.message.application.FriendAcceptedMessageApplicationService;
import com.gvchat.infrastructure.mq.MqConsumerFactory;
import com.gvchat.infrastructure.mq.MqMessageHandler;
import com.gvchat.infrastructure.mq.json.MqJsonCodec;
import com.gvchat.protocol.mq.event.FriendAcceptedEvent;
import com.gvchat.protocol.mq.group.ImMqConsumerGroups;
import com.gvchat.protocol.mq.topic.ImMqTopics;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FriendAcceptedEventConsumerTest {
  @Test
  void registersDedicatedGroupAndDelegatesDecodedEvent() throws Exception {
    RecordingFactory factory = new RecordingFactory();
    FriendAcceptedMessageApplicationService service = mock(FriendAcceptedMessageApplicationService.class);
    FriendAcceptedEventConsumer consumer = new FriendAcceptedEventConsumer(factory,
        new MqJsonCodec(new ObjectMapper()), service);

    consumer.start();
    factory.handler.get().handle(
        "{\"eventId\":\"evt-1\",\"requestId\":7,\"fromUserId\":11,\"toUserId\":22}"
            .getBytes(StandardCharsets.UTF_8));

    assertEquals(ImMqTopics.FRIEND_ACCEPTED_EVENT, factory.topic);
    assertEquals(ImMqConsumerGroups.MESSAGE_SERVICE_FRIEND_ACCEPTED, factory.group);
    verify(service).handle(any(FriendAcceptedEvent.class));
    consumer.stop();
  }

  @Test
  void propagatesApplicationFailureToMqHandler() throws Exception {
    RecordingFactory factory = new RecordingFactory();
    FriendAcceptedMessageApplicationService service = mock(FriendAcceptedMessageApplicationService.class);
    doThrow(new IllegalStateException("retry")).when(service).handle(any(FriendAcceptedEvent.class));
    FriendAcceptedEventConsumer consumer = new FriendAcceptedEventConsumer(factory,
        new MqJsonCodec(new ObjectMapper()), service);
    consumer.start();

    assertThrows(IllegalStateException.class, () -> factory.handler.get().handle(
        "{\"eventId\":\"evt-1\",\"requestId\":7,\"fromUserId\":11,\"toUserId\":22}"
            .getBytes(StandardCharsets.UTF_8)));
    consumer.stop();
  }

  private static final class RecordingFactory implements MqConsumerFactory {
    private String topic;
    private String group;
    private AtomicReference<MqMessageHandler> handler;

    @Override
    public AutoCloseable createOrderedConsumer(String topic, String consumerGroup, MqMessageHandler handler) {
      this.topic = topic;
      this.group = consumerGroup;
      this.handler = new AtomicReference<>(handler);
      return () -> { };
    }
  }
}
