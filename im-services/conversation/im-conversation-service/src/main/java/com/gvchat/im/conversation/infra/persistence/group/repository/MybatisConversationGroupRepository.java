package com.gvchat.im.conversation.infra.persistence.group.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gvchat.common.enums.GroupStatus;
import com.gvchat.im.conversation.domain.group.model.ConversationGroup;
import com.gvchat.im.conversation.domain.group.repository.ConversationGroupRepository;
import com.gvchat.im.conversation.infra.persistence.group.mapper.ConversationGroupMapper;
import com.gvchat.im.conversation.infra.persistence.group.mapper.ConversationMemberMapper;
import com.gvchat.im.conversation.infra.persistence.group.po.ConversationGroupPo;
import com.gvchat.im.conversation.infra.persistence.group.po.ConversationMemberPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisConversationGroupRepository implements ConversationGroupRepository {
  private final ConversationGroupMapper mapper;
  private final ConversationMemberMapper memberMapper;

  @Override
  public ConversationGroup save(ConversationGroup group) {
    ConversationGroupPo po = toPo(group);
    if (po.getId() == null) mapper.insert(po); else mapper.updateById(po);
    return fromPo(po);
  }

  @Override
  public Optional<ConversationGroup> findById(long groupId) { return Optional.ofNullable(mapper.selectById(groupId)).map(this::fromPo); }

  @Override
  public Optional<ConversationGroup> findByIdForUpdate(long groupId) {
    return Optional.ofNullable(mapper.selectOne(Wrappers.<ConversationGroupPo>lambdaQuery()
        .eq(ConversationGroupPo::getId, groupId).last("FOR UPDATE"))).map(this::fromPo);
  }

  @Override
  public List<ConversationGroup> findActiveByUserId(long userId) {
    List<Long> groupIds = memberMapper.selectList(Wrappers.<ConversationMemberPo>lambdaQuery()
        .eq(ConversationMemberPo::getUserId, userId)).stream().map(ConversationMemberPo::getGroupId).toList();
    if (groupIds.isEmpty()) return List.of();
    return mapper.selectList(Wrappers.<ConversationGroupPo>lambdaQuery().in(ConversationGroupPo::getId, groupIds)
        .eq(ConversationGroupPo::getStatus, GroupStatus.ACTIVE.name())).stream().map(this::fromPo).toList();
  }

  @Override
  public long count() { return mapper.selectCount(null); }

  @Override
  public long countCreatedSince(LocalDateTime since) {
    return mapper.selectCount(Wrappers.<ConversationGroupPo>lambdaQuery().ge(ConversationGroupPo::getCreatedAt, since));
  }

  @Override
  public List<Map<String, Object>> dailyCounts(LocalDateTime since) {
    QueryWrapper<ConversationGroupPo> wrapper = new QueryWrapper<ConversationGroupPo>()
        .select("DATE_FORMAT(created_at, '%Y-%m-%d') AS date", "COUNT(*) AS count")
        .ge("created_at", since)
        .groupBy("DATE_FORMAT(created_at, '%Y-%m-%d')")
        .orderByAsc("date");
    return mapper.selectMaps(wrapper);
  }

  @Override
  public List<ConversationGroup> search(String keyword, long offset, long limit) {
    return mapper.selectPage(new Page<>(offset / limit + 1, limit),
        Wrappers.<ConversationGroupPo>lambdaQuery()
            .like(keyword != null && !keyword.isBlank(), ConversationGroupPo::getName, keyword)
            .orderByDesc(ConversationGroupPo::getCreatedAt))
        .getRecords().stream().map(this::fromPo).toList();
  }

  @Override
  public long countByKeyword(String keyword) {
    return mapper.selectCount(Wrappers.<ConversationGroupPo>lambdaQuery()
        .like(keyword != null && !keyword.isBlank(), ConversationGroupPo::getName, keyword));
  }

  private ConversationGroup fromPo(ConversationGroupPo po) {
    return new ConversationGroup(po.getId(), po.getName(), po.getAvatar(), po.getOwnerId(), po.getAnnouncement(),
        po.getMaxMembers(), Boolean.TRUE.equals(po.getAllowMemberInvite()), Boolean.TRUE.equals(po.getAllowMemberFriendRequest()),
        Boolean.TRUE.equals(po.getAllowMemberViewAccount()),
        GroupStatus.valueOf(po.getStatus()), po.getAuthorizationVersion(), po.getCreatedAt(), po.getUpdatedAt());
  }

  private ConversationGroupPo toPo(ConversationGroup group) {
    ConversationGroupPo po = new ConversationGroupPo();
    po.setId(group.id()); po.setName(group.name()); po.setAvatar(group.avatar()); po.setOwnerId(group.ownerId());
    po.setAnnouncement(group.announcement()); po.setMaxMembers(group.maxMembers()); po.setAllowMemberInvite(group.allowMemberInvite());
    po.setAllowMemberFriendRequest(group.allowMemberFriendRequest());
    po.setAllowMemberViewAccount(group.allowMemberViewAccount());
    po.setStatus(group.status().name());
    po.setAuthorizationVersion(group.authorizationVersion()); po.setCreatedAt(group.createdAt()); po.setUpdatedAt(group.updatedAt());
    return po;
  }
}
