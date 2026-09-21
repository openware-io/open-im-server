package com.gvchat.im.user.infra.messaging.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.protocol.mq.event.UserProfileChangedEvent;
import com.gvchat.protocol.mq.topic.ImMqTopics;
import com.gvchat.im.user.domain.account.event.UserProfileChanged;
import com.gvchat.im.user.domain.account.port.UserProfileEventOutbox;
import com.gvchat.im.user.infra.messaging.outbox.mapper.UserOutboxMapper;
import com.gvchat.im.user.infra.messaging.outbox.po.UserOutboxPo;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 用户资料变更 Outbox 追加器：资料变更 -> 写入 user_outbox -> UserOutboxRelay 投递
 * {@link ImMqTopics#USER_PROFILE_CHANGED_EVENT}。openId 派生与手机号脱敏与 /oauth/userinfo 口径一致。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OutboxUserProfileEventAppender implements UserProfileEventOutbox {
  private final UserOutboxMapper userOutboxMapper;
  private final ObjectMapper objectMapper;

  @Override
  public void append(UserProfileChanged event) {
    UserProfileChangedEvent payload = UserProfileChangedEvent.builder()
        .eventId(event.eventId())
        .userId(event.userId())
        .openId(deriveOpenId(event.userId()))
        .nickname(event.nickname())
        .avatar(event.avatar())
        .phone(maskPhone(event.phone()))
        .occurredAt(event.occurredAt())
        .build();
    persist(payload.getEventId(), "user_account", String.valueOf(event.userId()),
        ImMqTopics.USER_PROFILE_CHANGED_EVENT, String.valueOf(event.userId()), payload);
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
      throw new IllegalStateException("用户资料 Outbox 事件落库失败", exception);
    }
  }

  /** 与 OpenPlatformApplicationService.deriveOpenId 保持一致（全局 open_id，与应用无关）。 */
  private String deriveOpenId(Long userId) {
    return "im_" + userId;
  }

  /** 与 /oauth/userinfo 的 maskPhone 口径一致（脱敏后同步）。 */
  private String maskPhone(String phone) {
    if (phone == null || phone.isBlank()) {
      return "";
    }
    if (phone.length() <= 7) {
      return phone.charAt(0) + "****";
    }
    return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
  }
}
