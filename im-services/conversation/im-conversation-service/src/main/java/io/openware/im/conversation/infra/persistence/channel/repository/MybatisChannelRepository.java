package io.openware.im.conversation.infra.persistence.channel.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.im.conversation.domain.channel.model.Channel;
import io.openware.im.conversation.domain.channel.repository.AuditSnapshot;
import io.openware.im.conversation.domain.channel.repository.ChannelRepository;
import io.openware.im.conversation.domain.channel.repository.Persisted;
import io.openware.im.conversation.infra.persistence.channel.mapper.ChannelMapper;
import io.openware.im.conversation.infra.persistence.channel.po.ChannelPo;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MybatisChannelRepository implements ChannelRepository {
  private final ChannelMapper channelMapper;

  @Override
  public Persisted<Channel> save(Channel channel) {
    ChannelPo po = toPo(channel);
    LocalDateTime now = LocalDateTime.now();
    if (po.getId() == null) {
      po.setCreatedAt(now);
      po.setUpdatedAt(now);
      channelMapper.insert(po);
      return toPersisted(po);
    }
    po.setUpdatedAt(now);
    channelMapper.updateById(po);
    // updateById 不写 created_*（NOT_NULL 策略），响应仍要回显它们，所以回读一次。
    return toPersisted(channelMapper.selectById(po.getId()));
  }

  @Override
  public Optional<Persisted<Channel>> findById(long channelId) {
    return Optional.ofNullable(channelMapper.selectById(channelId)).map(this::toPersisted);
  }

  @Override
  public List<Persisted<Channel>> findByIds(Collection<Long> channelIds) {
    if (channelIds == null || channelIds.isEmpty()) {
      return List.of();
    }
    return channelMapper.selectByIds(channelIds).stream().map(this::toPersisted).toList();
  }

  @Override
  public Optional<Persisted<Channel>> findByCode(String code) {
    if (code == null || code.isBlank()) {
      return Optional.empty();
    }
    ChannelPo po = channelMapper.selectOne(new LambdaQueryWrapper<ChannelPo>()
        .eq(ChannelPo::getCode, code.trim().toUpperCase())
        .last("LIMIT 1"));
    return Optional.ofNullable(po).map(this::toPersisted);
  }

  @Override
  public List<Persisted<Channel>> searchByName(String keyword, int limit) {
    int safeLimit = Math.min(limit <= 0 ? 20 : limit, 50);
    LambdaQueryWrapper<ChannelPo> wrapper = new LambdaQueryWrapper<ChannelPo>()
        .eq(ChannelPo::getStatus, "active")
        .orderByDesc(ChannelPo::getId)
        .last("LIMIT " + safeLimit);
    if (keyword != null && !keyword.isBlank()) {
      wrapper.like(ChannelPo::getName, keyword.trim());
    }
    return channelMapper.selectList(wrapper).stream().map(this::toPersisted).toList();
  }

  @Override
  public void deleteById(long channelId) {
    channelMapper.deleteById(channelId);
  }

  private ChannelPo toPo(Channel channel) {
    ChannelPo po = new ChannelPo();
    po.setId(channel.getId());
    po.setCode(channel.getCode());
    po.setOwnerId(channel.getOwnerId());
    po.setName(channel.getName());
    po.setAvatar(channel.getAvatar());
    po.setAnnouncement(channel.getAnnouncement());
    po.setDiscussionGroupId(channel.getDiscussionGroupId());
    po.setStatus(channel.getStatus());
    return po;
  }

  private Channel toDomain(ChannelPo po) {
    return Channel.restore(po.getId(), po.getCode(), po.getOwnerId(), po.getName(), po.getAvatar(),
        po.getAnnouncement(), po.getDiscussionGroupId(), po.getStatus());
  }

  /** 业务聚合 + 记账快照：响应要回显四个审计列，而它们不进领域模型。 */
  private Persisted<Channel> toPersisted(ChannelPo po) {
    if (po == null) {
      return null;
    }
    return new Persisted<>(toDomain(po),
        new AuditSnapshot(po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt()));
  }
}
