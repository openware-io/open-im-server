package io.openware.im.user.infra.messaging.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.protocol.mq.event.FriendAcceptedEvent;
import io.openware.protocol.mq.event.FriendRequestedEvent;
import io.openware.protocol.mq.topic.ImMqTopics;
import io.openware.im.user.domain.social.event.FriendAccepted;
import io.openware.im.user.domain.social.event.FriendRequested;
import io.openware.im.user.domain.social.port.FriendEventOutbox;
import io.openware.im.user.infra.messaging.outbox.mapper.UserOutboxMapper;
import io.openware.im.user.infra.messaging.outbox.po.UserOutboxPo;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class OutboxFriendEventAppender implements FriendEventOutbox {
  private final UserOutboxMapper userOutboxMapper;
  private final ObjectMapper objectMapper;

  @Override
  public void append(FriendRequested event) {
    FriendRequestedEvent payload = FriendRequestedEvent.builder()
        .eventId(UUID.randomUUID().toString())
        .requestId(event.requestId())
        .fromUserId(event.fromUserId())
        .toUserId(event.toUserId())
        .message(event.message())
        .occurredAt(Instant.now())
        .build();
    persist(payload.getEventId(), "friend_request", String.valueOf(event.requestId()), ImMqTopics.FRIEND_REQUESTED_EVENT,
        String.valueOf(event.toUserId()), payload);
  }

  @Override
  public void append(FriendAccepted event) {
    FriendAcceptedEvent payload = FriendAcceptedEvent.builder()
        .eventId(UUID.randomUUID().toString())
        .requestId(event.requestId())
        .fromUserId(event.fromUserId())
        .toUserId(event.toUserId())
        .occurredAt(Instant.now())
        .build();
    persist(payload.getEventId(), "friend_request", String.valueOf(event.requestId()), ImMqTopics.FRIEND_ACCEPTED_EVENT,
        String.valueOf(event.fromUserId()), payload);
  }

  private void persist(String eventId, String aggregateType, String aggregateId, String topic, String shardingKey,
      Object payload) {
    try {
      UserOutboxPo row = new UserOutboxPo();
      row.setEventId(eventId);
      row.setAggregateType(aggregateType);
      row.setAggregateId(aggregateId);
      row.setTopic(topic);
      row.setShardingKey(shardingKey);
      row.setPayloadJson(objectMapper.writeValueAsString(payload));
      row.setPublished(Boolean.FALSE);
      row.setCreatedAt(LocalDateTime.now());
      userOutboxMapper.insert(row);
      log.info("好友领域 Outbox 事件已落库, eventId={}, aggregateId={}, topic={}", eventId, aggregateId, topic);
    } catch (Exception exception) {
      log.error("好友领域 Outbox 事件落库失败, eventId={}, aggregateId={}, topic={}", eventId, aggregateId, topic, exception);
      throw new IllegalStateException("好友领域 Outbox 事件落库失败", exception);
    }
  }
}
