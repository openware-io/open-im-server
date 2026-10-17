package io.openware.im.user.domain.notificationsetting.model;

/**
 * 用户离线推送通知设置：私聊/群聊/频道三类分别开关（默认全开）。
 *
 * <p><b>为什么不带审计列</b>（standards/10 L299）：{@code createdAt}/{@code updatedAt} 是记账信息而非业务事实，
 * 由适配器在写库时确定。这两个值在本服务里**没有任何领域或应用层读者**——只有适配器把它们映射到 PO，
 * 所以不需要额外的载体类型，直接下沉即可。
 */
public class UserNotificationSetting {
  private Long userId;
  private boolean notifyPrivate;
  private boolean notifyGroup;
  private boolean notifyChannel;

  public static UserNotificationSetting defaults(long userId) {
    UserNotificationSetting setting = new UserNotificationSetting();
    setting.userId = userId;
    setting.notifyPrivate = true;
    setting.notifyGroup = true;
    setting.notifyChannel = true;
    return setting;
  }

  public void update(boolean notifyPrivate, boolean notifyGroup, boolean notifyChannel) {
    this.notifyPrivate = notifyPrivate;
    this.notifyGroup = notifyGroup;
    this.notifyChannel = notifyChannel;
  }

  public Long getUserId() { return userId; }
  public boolean isNotifyPrivate() { return notifyPrivate; }
  public boolean isNotifyGroup() { return notifyGroup; }
  public boolean isNotifyChannel() { return notifyChannel; }

  public void restore(Long userId, boolean notifyPrivate, boolean notifyGroup, boolean notifyChannel) {
    this.userId = userId;
    this.notifyPrivate = notifyPrivate;
    this.notifyGroup = notifyGroup;
    this.notifyChannel = notifyChannel;
  }
}
