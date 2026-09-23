package io.openware.im.conversation.infra.persistence.channel.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.im.conversation.domain.channel.model.ChannelSubscription;
import io.openware.im.conversation.domain.channel.repository.ChannelSubscriptionRepository;
import io.openware.im.conversation.infra.persistence.channel.mapper.ChannelSubscriptionMapper;
import io.openware.im.conversation.infra.persistence.channel.po.ChannelSubscriptionPo;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MybatisChannelSubscriptionRepository implements ChannelSubscriptionRepository {
  private final ChannelSubscriptionMapper subscriptionMapper;

  @Override
  public Optional<ChannelSubscription> findByChannelIdAndUserId(long channelId, long userId) {
    ChannelSubscriptionPo po = subscriptionMapper.selectOne(new LambdaQueryWrapper<ChannelSubscriptionPo>()
        .eq(ChannelSubscriptionPo::getChannelId, channelId)
        .eq(ChannelSubscriptionPo::getUserId, userId));
    return Optional.ofNullable(po).map(this::toDomain);
  }

  @Override
  public List<ChannelSubscription> findByUserId(long userId) {
    return subscriptionMapper.selectList(new LambdaQueryWrapper<ChannelSubscriptionPo>()
        .eq(ChannelSubscriptionPo::getUserId, userId)).stream().map(this::toDomain).toList();
  }

  @Override
  public List<Long> listSubscriberUserIds(long channelId) {
    return subscriptionMapper.selectList(new LambdaQueryWrapper<ChannelSubscriptionPo>()
        .select(ChannelSubscriptionPo::getUserId)
        .eq(ChannelSubscriptionPo::getChannelId, channelId)).stream()
        .map(ChannelSubscriptionPo::getUserId).toList();
  }

  @Override
  public long countByChannelId(long channelId) {
    Long count = subscriptionMapper.selectCount(new LambdaQueryWrapper<ChannelSubscriptionPo>()
        .eq(ChannelSubscriptionPo::getChannelId, channelId));
    return count == null ? 0 : count;
  }

  @Override
  public ChannelSubscription save(ChannelSubscription subscription) {
    ChannelSubscriptionPo po = toPo(subscription);
    if (po.getId() == null) {
      subscriptionMapper.insert(po);
    } else {
      subscriptionMapper.updateById(po);
    }
    return toDomain(po);
  }

  @Override
  public int deleteByChannelIdAndUserId(long channelId, long userId) {
    return subscriptionMapper.delete(new LambdaQueryWrapper<ChannelSubscriptionPo>()
        .eq(ChannelSubscriptionPo::getChannelId, channelId)
        .eq(ChannelSubscriptionPo::getUserId, userId));
  }

  @Override
  public int deleteByChannelId(long channelId) {
    return subscriptionMapper.delete(new LambdaQueryWrapper<ChannelSubscriptionPo>()
        .eq(ChannelSubscriptionPo::getChannelId, channelId));
  }

  private ChannelSubscriptionPo toPo(ChannelSubscription subscription) {
    ChannelSubscriptionPo po = new ChannelSubscriptionPo();
    po.setId(subscription.getId());
    po.setChannelId(subscription.getChannelId());
    po.setUserId(subscription.getUserId());
    po.setNotifySetting(subscription.getNotifySetting());
    po.setJoinedAt(subscription.getCreatedAt());
    po.setCreatedBy(subscription.getCreatedBy());
    po.setCreatedAt(subscription.getCreatedAt());
    po.setUpdatedBy(subscription.getUpdatedBy());
    po.setUpdatedAt(subscription.getUpdatedAt());
    return po;
  }

  private ChannelSubscription toDomain(ChannelSubscriptionPo po) {
    return ChannelSubscription.restore(po.getId(), po.getChannelId(), po.getUserId(), po.getNotifySetting(),
        po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt());
  }
}
