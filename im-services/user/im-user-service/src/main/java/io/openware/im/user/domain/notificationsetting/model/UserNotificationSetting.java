package io.openware.im.user.domain.notificationsetting.model;

import java.time.LocalDateTime;

/** 用户离线推送通知设置：私聊/群聊/频道三类分别开关（默认全开）。 */
public class UserNotificationSetting {
  private Long userId;
  private boolean notifyPrivate;
  private boolean notifyGroup;
  private boolean notifyChannel;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public static UserNotificationSetting defaults(long userId, LocalDateTime occurredAt) {
    UserNotificationSetting setting = new UserNotificationSetting();
    setting.userId = userId;
    setting.notifyPrivate = true;
    setting.notifyGroup = true;
    setting.notifyChannel = true;
    setting.createdAt = occurredAt;
    setting.updatedAt = occurredAt;
    return setting;
  }

  public void update(boolean notifyPrivate, boolean notifyGroup, boolean notifyChannel, LocalDateTime occurredAt) {
    this.notifyPrivate = notifyPrivate;
    this.notifyGroup = notifyGroup;
    this.notifyChannel = notifyChannel;
    this.updatedAt = occurredAt;
  }

  public Long getUserId() { return userId; }
  public boolean isNotifyPrivate() { return notifyPrivate; }
  public boolean isNotifyGroup() { return notifyGroup; }
  public boolean isNotifyChannel() { return notifyChannel; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }

  public void restore(Long userId, boolean notifyPrivate, boolean notifyGroup, boolean notifyChannel,
      LocalDateTime createdAt, LocalDateTime updatedAt) {
    this.userId = userId;
    this.notifyPrivate = notifyPrivate;
    this.notifyGroup = notifyGroup;
    this.notifyChannel = notifyChannel;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }
}
