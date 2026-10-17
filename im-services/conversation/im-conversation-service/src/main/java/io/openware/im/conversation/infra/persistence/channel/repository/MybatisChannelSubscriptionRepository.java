package io.openware.im.conversation.infra.persistence.channel.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.im.conversation.domain.channel.model.ChannelSubscription;
import io.openware.im.conversation.domain.channel.repository.ChannelSubscriptionRepository;
import io.openware.im.conversation.infra.persistence.channel.mapper.ChannelSubscriptionMapper;
import io.openware.im.conversation.infra.persistence.channel.po.ChannelSubscriptionPo;
import java.time.LocalDateTime;
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
    // 审计列在适配器确定（standards/10）：joined_at 与 created_at 同源——订阅时刻即加入时刻。
    LocalDateTime now = LocalDateTime.now();
    po.setJoinedAt(now);
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    return po;
  }

  private ChannelSubscription toDomain(ChannelSubscriptionPo po) {
    return ChannelSubscription.restore(po.getId(), po.getChannelId(), po.getUserId(), po.getNotifySetting());
  }
}
