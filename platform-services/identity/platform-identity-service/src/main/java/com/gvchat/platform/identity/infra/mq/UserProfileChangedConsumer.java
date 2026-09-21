package com.gvchat.platform.identity.infra.mq;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.infrastructure.mq.MqConsumerFactory;
import com.gvchat.infrastructure.mq.json.MqJsonCodec;
import com.gvchat.platform.identity.infra.persistence.mapper.OauthLinkMapper;
import com.gvchat.platform.identity.infra.persistence.mapper.ProfileSyncRecordMapper;
import com.gvchat.platform.identity.infra.persistence.po.OauthLinkPo;
import com.gvchat.platform.identity.infra.persistence.po.ProfileSyncRecordPo;
import com.gvchat.protocol.mq.event.UserProfileChangedEvent;
import com.gvchat.protocol.mq.group.ImMqConsumerGroups;
import com.gvchat.protocol.mq.topic.ImMqTopics;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * UserProfileChanged 事件消费者（IM -> SaaS）。按 idt_oauth_link.scope 过滤授权范围，
 * 落 idt_profile_sync_record 资料快照；event_id 唯一键兜底消费幂等，无绑定/无授权字段直接跳过。
 */
@Component
@Slf4j
public class UserProfileChangedConsumer implements SmartLifecycle {
  private static final String PROVIDER_IM = "IM";
  private static final String SCOPE_PROFILE_BASIC = "profile.basic";
  private static final String SCOPE_PROFILE_PHONE = "profile.phone";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final MqConsumerFactory mqConsumerFactory;
  private final MqJsonCodec mqJsonCodec;
  private final OauthLinkMapper oauthLinkMapper;
  private final ProfileSyncRecordMapper profileSyncRecordMapper;

  private volatile AutoCloseable consumer;
  private volatile boolean running;

  public UserProfileChangedConsumer(MqConsumerFactory mqConsumerFactory, MqJsonCodec mqJsonCodec,
      OauthLinkMapper oauthLinkMapper, ProfileSyncRecordMapper profileSyncRecordMapper) {
    this.mqConsumerFactory = mqConsumerFactory;
    this.mqJsonCodec = mqJsonCodec;
    this.oauthLinkMapper = oauthLinkMapper;
    this.profileSyncRecordMapper = profileSyncRecordMapper;
  }

  @Override
  public void start() {
    if (running) {
      return;
    }
    try {
      consumer = mqConsumerFactory.createOrderedConsumer(ImMqTopics.USER_PROFILE_CHANGED_EVENT,
          ImMqConsumerGroups.IDENTITY_SERVICE_USER_PROFILE_CHANGED,
          body -> consume(mqJsonCodec.fromBytes(body, UserProfileChangedEvent.class)));
      running = true;
      log.info("Started user profile changed consumer, topic={}, consumerGroup={}",
          ImMqTopics.USER_PROFILE_CHANGED_EVENT,
          ImMqConsumerGroups.IDENTITY_SERVICE_USER_PROFILE_CHANGED);
    } catch (Exception exception) {
      log.error("Failed to start user profile changed consumer.", exception);
      throw new IllegalStateException("Failed to start user profile changed consumer.", exception);
    }
  }

  private void consume(UserProfileChangedEvent event) {
    OauthLinkPo link = findLink(event.getOpenId());
    if (link == null) {
      log.info("UserProfileChanged 无 IM 绑定，跳过, openId={}", event.getOpenId());
      return;
    }
    Map<String, Object> profile = filterByScope(link.getScope(), event);
    if (profile.isEmpty()) {
      log.info("UserProfileChanged 授权范围内无资料字段，跳过, openId={}, scope={}", event.getOpenId(),
          link.getScope());
      return;
    }
    try {
      ProfileSyncRecordPo record = new ProfileSyncRecordPo();
      record.setEventId(event.getEventId());
      record.setAccountId(link.getAccountId());
      record.setProvider(PROVIDER_IM);
      record.setProfileJson(OBJECT_MAPPER.writeValueAsString(profile));
      record.setOccurredAt(LocalDateTime.now());
      profileSyncRecordMapper.insert(record);
      log.info("UserProfileChanged 同步资料成功, openId={}, accountId={}, fields={}", event.getOpenId(),
          link.getAccountId(), profile.keySet());
    } catch (DuplicateKeyException duplicate) {
      log.info("UserProfileChanged 事件已消费，幂等跳过, eventId={}", event.getEventId());
    } catch (Exception exception) {
      log.error("UserProfileChanged 同步资料失败, eventId={}, openId={}", event.getEventId(), event.getOpenId(),
          exception);
      throw new IllegalStateException("UserProfileChanged 同步资料失败, eventId=" + event.getEventId(), exception);
    }
  }

  private Map<String, Object> filterByScope(String scope, UserProfileChangedEvent event) {
    Map<String, Object> profile = new LinkedHashMap<>();
    Set<String> scopes = scope == null ? Set.of()
        : Arrays.stream(scope.trim().split("\\s+")).filter(s -> !s.isBlank()).collect(Collectors.toSet());
    if (scopes.contains(SCOPE_PROFILE_BASIC)) {
      profile.put("nickname", event.getNickname());
      profile.put("avatar", event.getAvatar());
    }
    if (scopes.contains(SCOPE_PROFILE_PHONE)) {
      profile.put("phone", event.getPhone());
    }
    return profile;
  }

  private OauthLinkPo findLink(String openId) {
    QueryWrapper<OauthLinkPo> qw = new QueryWrapper<>();
    qw.eq("provider", PROVIDER_IM).eq("provider_account_id", openId).eq("status", "ACTIVE");
    return oauthLinkMapper.selectOne(qw);
  }

  @Override
  public void stop() {
    running = false;
    if (consumer != null) {
      try {
        consumer.close();
      } catch (Exception exception) {
        log.warn("Failed to close user profile changed consumer cleanly.", exception);
      } finally {
        consumer = null;
      }
    }
    log.info("Stopped user profile changed consumer.");
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
    return Integer.MAX_VALUE - 200;
  }
}
