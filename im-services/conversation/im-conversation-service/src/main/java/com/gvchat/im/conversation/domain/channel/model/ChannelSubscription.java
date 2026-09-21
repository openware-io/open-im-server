package com.gvchat.im.conversation.domain.channel.model;

import java.time.LocalDateTime;

/** 频道订阅实体：用户与频道的订阅关系（幂等去重由唯一约束与领域行为保障）。 */
public class ChannelSubscription {
  private final Long id;
  private final Long channelId;
  private final Long userId;
  private final String notifySetting;
  private final Long createdBy;
  private final LocalDateTime createdAt;
  private final Long updatedBy;
  private final LocalDateTime updatedAt;

  private ChannelSubscription(Long id, Long channelId, Long userId, String notifySetting,
      Long createdBy, LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
    this.id = id;
    this.channelId = channelId;
    this.userId = userId;
    this.notifySetting = notifySetting;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedBy = updatedBy;
    this.updatedAt = updatedAt;
  }

  public static ChannelSubscription subscribe(long channelId, long userId, long operatorId, LocalDateTime occurredAt) {
    return new ChannelSubscription(null, channelId, userId, "default", operatorId, occurredAt, operatorId, occurredAt);
  }

  public static ChannelSubscription restore(Long id, Long channelId, Long userId, String notifySetting,
      Long createdBy, LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
    return new ChannelSubscription(id, channelId, userId, notifySetting, createdBy, createdAt, updatedBy, updatedAt);
  }

  public Long getId() { return id; }
  public Long getChannelId() { return channelId; }
  public Long getUserId() { return userId; }
  public String getNotifySetting() { return notifySetting; }
  public Long getCreatedBy() { return createdBy; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public Long getUpdatedBy() { return updatedBy; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
}
