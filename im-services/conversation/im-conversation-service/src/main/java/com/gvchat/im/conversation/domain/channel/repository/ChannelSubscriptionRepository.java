package com.gvchat.im.conversation.domain.channel.repository;

import com.gvchat.im.conversation.domain.channel.model.ChannelSubscription;
import java.util.List;
import java.util.Optional;

public interface ChannelSubscriptionRepository {
  Optional<ChannelSubscription> findByChannelIdAndUserId(long channelId, long userId);

  List<ChannelSubscription> findByUserId(long userId);

  /** 频道的全部订阅者用户 id（用于消息通知 fan-out）。 */
  List<Long> listSubscriberUserIds(long channelId);

  long countByChannelId(long channelId);

  ChannelSubscription save(ChannelSubscription subscription);

  /** 取消订阅（返回删除行数；未订阅时为 0）。 */
  int deleteByChannelIdAndUserId(long channelId, long userId);

  /** 删除频道时清理其全部订阅关系。 */
  int deleteByChannelId(long channelId);
}
