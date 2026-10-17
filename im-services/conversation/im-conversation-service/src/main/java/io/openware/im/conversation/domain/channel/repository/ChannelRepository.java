package io.openware.im.conversation.domain.channel.repository;

import io.openware.im.conversation.domain.channel.model.Channel;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChannelRepository {
  Persisted<Channel> save(Channel channel);

  Optional<Persisted<Channel>> findById(long channelId);

  List<Persisted<Channel>> findByIds(Collection<Long> channelIds);

  Optional<Persisted<Channel>> findByCode(String code);

  /** 按名称模糊搜索（keyword 为空时返回最近创建的频道，limit 兜底上限）。 */
  List<Persisted<Channel>> searchByName(String keyword, int limit);

  /** 删除频道（硬删除）。 */
  void deleteById(long channelId);
}
