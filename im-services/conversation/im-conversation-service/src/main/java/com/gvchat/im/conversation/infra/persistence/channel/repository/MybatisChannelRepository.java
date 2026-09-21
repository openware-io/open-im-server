package com.gvchat.im.conversation.infra.persistence.channel.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gvchat.im.conversation.domain.channel.model.Channel;
import com.gvchat.im.conversation.domain.channel.repository.ChannelRepository;
import com.gvchat.im.conversation.infra.persistence.channel.mapper.ChannelMapper;
import com.gvchat.im.conversation.infra.persistence.channel.po.ChannelPo;
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
  public Channel save(Channel channel) {
    ChannelPo po = toPo(channel);
    if (po.getId() == null) {
      channelMapper.insert(po);
    } else {
      channelMapper.updateById(po);
    }
    return toDomain(po);
  }

  @Override
  public Optional<Channel> findById(long channelId) {
    return Optional.ofNullable(channelMapper.selectById(channelId)).map(this::toDomain);
  }

  @Override
  public List<Channel> findByIds(Collection<Long> channelIds) {
    if (channelIds == null || channelIds.isEmpty()) {
      return List.of();
    }
    return channelMapper.selectByIds(channelIds).stream().map(this::toDomain).toList();
  }

  @Override
  public Optional<Channel> findByCode(String code) {
    if (code == null || code.isBlank()) {
      return Optional.empty();
    }
    ChannelPo po = channelMapper.selectOne(new LambdaQueryWrapper<ChannelPo>()
        .eq(ChannelPo::getCode, code.trim().toUpperCase())
        .last("LIMIT 1"));
    return Optional.ofNullable(po).map(this::toDomain);
  }

  @Override
  public List<Channel> searchByName(String keyword, int limit) {
    int safeLimit = Math.min(limit <= 0 ? 20 : limit, 50);
    LambdaQueryWrapper<ChannelPo> wrapper = new LambdaQueryWrapper<ChannelPo>()
        .eq(ChannelPo::getStatus, "active")
        .orderByDesc(ChannelPo::getId)
        .last("LIMIT " + safeLimit);
    if (keyword != null && !keyword.isBlank()) {
      wrapper.like(ChannelPo::getName, keyword.trim());
    }
    return channelMapper.selectList(wrapper).stream().map(this::toDomain).toList();
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
    po.setCreatedBy(channel.getCreatedBy());
    po.setCreatedAt(channel.getCreatedAt());
    po.setUpdatedBy(channel.getUpdatedBy());
    po.setUpdatedAt(channel.getUpdatedAt());
    return po;
  }

  private Channel toDomain(ChannelPo po) {
    return Channel.restore(po.getId(), po.getCode(), po.getOwnerId(), po.getName(), po.getAvatar(),
        po.getAnnouncement(), po.getDiscussionGroupId(), po.getStatus(), po.getCreatedBy(), po.getCreatedAt(),
        po.getUpdatedBy(), po.getUpdatedAt());
  }
}
