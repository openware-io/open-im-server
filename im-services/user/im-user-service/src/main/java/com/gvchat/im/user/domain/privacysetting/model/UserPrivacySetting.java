package com.gvchat.im.user.domain.privacysetting.model;

import java.time.LocalDateTime;

/** 用户隐私设置（含「允许通过群聊添加我为好友」与「隐藏非好友群成员用户名头像」，均默认开启）。 */
public class UserPrivacySetting {
  private Long userId;
  private boolean allowGroupFriendRequest;
  private boolean hideGroupMemberInfo;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public static UserPrivacySetting defaults(long userId, LocalDateTime occurredAt) {
    UserPrivacySetting setting = new UserPrivacySetting();
    setting.userId = userId;
    setting.allowGroupFriendRequest = true;
    setting.hideGroupMemberInfo = true;
    setting.createdAt = occurredAt;
    setting.updatedAt = occurredAt;
    return setting;
  }

  public void update(boolean allowGroupFriendRequest, boolean hideGroupMemberInfo, LocalDateTime occurredAt) {
    this.allowGroupFriendRequest = allowGroupFriendRequest;
    this.hideGroupMemberInfo = hideGroupMemberInfo;
    this.updatedAt = occurredAt;
  }

  public Long getUserId() { return userId; }
  public boolean isAllowGroupFriendRequest() { return allowGroupFriendRequest; }
  public boolean isHideGroupMemberInfo() { return hideGroupMemberInfo; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }

  public void restore(Long userId, boolean allowGroupFriendRequest, boolean hideGroupMemberInfo, LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    this.userId = userId;
    this.allowGroupFriendRequest = allowGroupFriendRequest;
    this.hideGroupMemberInfo = hideGroupMemberInfo;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }
}
