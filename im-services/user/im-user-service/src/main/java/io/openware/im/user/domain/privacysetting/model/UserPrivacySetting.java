package io.openware.im.user.domain.privacysetting.model;

/**
 * 用户隐私设置（含「允许通过群聊添加我为好友」与「隐藏非好友群成员用户名头像」，均默认开启）。
 *
 * <p><b>为什么不带审计列</b>（standards/10 L299）：{@code createdAt}/{@code updatedAt} 是记账信息而非业务事实，
 * 由适配器在写库时确定。这两个值在本服务里**没有任何领域或应用层读者**——只有适配器把它们映射到 PO，
 * 所以不需要额外的载体类型，直接下沉即可。
 */
public class UserPrivacySetting {
  private Long userId;
  private boolean allowGroupFriendRequest;
  private boolean hideGroupMemberInfo;

  public static UserPrivacySetting defaults(long userId) {
    UserPrivacySetting setting = new UserPrivacySetting();
    setting.userId = userId;
    setting.allowGroupFriendRequest = true;
    setting.hideGroupMemberInfo = true;
    return setting;
  }

  public void update(boolean allowGroupFriendRequest, boolean hideGroupMemberInfo) {
    this.allowGroupFriendRequest = allowGroupFriendRequest;
    this.hideGroupMemberInfo = hideGroupMemberInfo;
  }

  public Long getUserId() { return userId; }
  public boolean isAllowGroupFriendRequest() { return allowGroupFriendRequest; }
  public boolean isHideGroupMemberInfo() { return hideGroupMemberInfo; }

  public void restore(Long userId, boolean allowGroupFriendRequest, boolean hideGroupMemberInfo) {
    this.userId = userId;
    this.allowGroupFriendRequest = allowGroupFriendRequest;
    this.hideGroupMemberInfo = hideGroupMemberInfo;
  }
}
