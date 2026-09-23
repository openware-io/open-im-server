package io.openware.im.message.domain.message.port;

import java.util.List;

public interface ChannelMembershipPort {
  boolean isOwner(long channelId, long userId);

  boolean isSubscribed(long channelId, long userId);

  /** 频道全部订阅者用户 id（消息通知 fan-out 用）。 */
  List<Long> listSubscriberUserIds(long channelId);
}
