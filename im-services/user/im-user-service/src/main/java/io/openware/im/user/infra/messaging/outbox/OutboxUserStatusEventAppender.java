package io.openware.im.user.infra.messaging.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.protocol.mq.topic.ImMqTopics;
import io.openware.protocol.mq.event.UserStatusChangedEvent;
import io.openware.protocol.mq.event.UserAuthenticationInvalidatedEvent;
import io.openware.protocol.mq.event.UserChatRecordsPurgeEvent;
import io.openware.protocol.mq.event.UserDataWipeRequestedEvent;
import io.openware.im.user.domain.account.event.UserChatRecordsPurged;
import io.openware.im.user.domain.account.event.UserAuthenticationInvalidated;
import io.openware.im.user.domain.account.event.UserDataWipeRequested;
import io.openware.im.user.domain.account.event.UserStatusChanged;
import io.openware.im.user.domain.account.port.UserStatusEventOutbox;
import io.openware.im.user.infra.messaging.outbox.mapper.UserOutboxMapper;
import io.openware.im.user.infra.messaging.outbox.po.UserOutboxPo;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxUserStatusEventAppender implements UserStatusEventOutbox {
  private final UserOutboxMapper userOutboxMapper;
  private final ObjectMapper objectMapper;

  @Override
  public void append(UserStatusChanged event) {
    persist(event.eventId(), "user_account", String.valueOf(event.userId()), ImMqTopics.USER_STATUS_CHANGED_EVENT,
        String.valueOf(event.userId()), UserStatusChangedEvent.builder().eventId(event.eventId()).userId(event.userId())
            .previousStatus(event.previousStatus().name()).status(event.status().name()).statusVersion(event.statusVersion())
            .operatorId(event.operatorId()).reason(event.reason()).correlationId(event.correlationId())
            .occurredAt(event.occurredAt()).build());
  }

  @Override
  public void append(UserAuthenticationInvalidated event) {
    persist(event.eventId(), "user_account", String.valueOf(event.userId()),
        ImMqTopics.USER_AUTHENTICATION_INVALIDATED_EVENT, String.valueOf(event.userId()),
        new UserAuthenticationInvalidatedEvent(
            event.eventId(), event.userId(), event.statusVersion(), event.occurredAt()));
  }

  @Override
  public void append(UserChatRecordsPurged event) {
    persist(event.eventId(), "user_account", String.valueOf(event.userId()),
        ImMqTopics.USER_CHAT_RECORDS_PURGE_EVENT, String.valueOf(event.userId()),
        UserChatRecordsPurgeEvent.builder().eventId(event.eventId()).userId(event.userId())
            .occurredAt(event.occurredAt()).build());
  }

  @Override
  public void append(UserDataWipeRequested event) {
    persist(event.eventId(), "user_account", String.valueOf(event.userId()),
        ImMqTopics.USER_DATA_WIPE_REQUESTED_EVENT, String.valueOf(event.userId()),
        new UserDataWipeRequestedEvent(event.eventId(), event.userId(), event.occurredAt()));
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
    } catch (Exception exception) {
      throw new IllegalStateException("用户状态 Outbox 事件落库失败", exception);
    }
  }
}
