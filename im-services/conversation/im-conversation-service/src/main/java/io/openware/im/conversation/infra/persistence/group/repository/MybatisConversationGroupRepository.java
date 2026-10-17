package io.openware.im.conversation.infra.persistence.group.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.common.enums.GroupStatus;
import io.openware.im.conversation.domain.group.model.ConversationGroup;
import io.openware.im.conversation.domain.group.repository.AuditSnapshot;
import io.openware.im.conversation.domain.group.repository.ConversationGroupRepository;
import io.openware.im.conversation.domain.group.repository.Persisted;
import io.openware.im.conversation.infra.persistence.group.mapper.ConversationGroupMapper;
import io.openware.im.conversation.infra.persistence.group.mapper.ConversationMemberMapper;
import io.openware.im.conversation.infra.persistence.group.po.ConversationGroupPo;
import io.openware.im.conversation.infra.persistence.group.po.ConversationMemberPo;
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
  public Persisted<ConversationGroup> save(ConversationGroup group, LocalDateTime occurredAt) {
    ConversationGroupPo po = toPo(group);
    if (po.getId() == null) {
      // 插入：created_at / updated_at 都取本次落库时刻。
      po.setCreatedAt(occurredAt);
      po.setUpdatedAt(occurredAt);
      mapper.insert(po);
    } else {
      // 更新：只写 updated_at；created_at 保持原值（updateById 会跳过 null 列）。
      po.setUpdatedAt(occurredAt);
      mapper.updateById(po);
    }
    return findById(po.getId()).orElseThrow();
  }

  @Override
  public Optional<Persisted<ConversationGroup>> findById(long groupId) {
    return Optional.ofNullable(mapper.selectById(groupId)).map(this::toPersisted);
  }

  @Override
  public Optional<Persisted<ConversationGroup>> findByIdForUpdate(long groupId) {
    return Optional.ofNullable(mapper.selectOne(Wrappers.<ConversationGroupPo>lambdaQuery()
        .eq(ConversationGroupPo::getId, groupId).last("FOR UPDATE"))).map(this::toPersisted);
  }

  @Override
  public List<Persisted<ConversationGroup>> findActiveByUserId(long userId) {
    List<Long> groupIds = memberMapper.selectList(Wrappers.<ConversationMemberPo>lambdaQuery()
        .eq(ConversationMemberPo::getUserId, userId)).stream().map(ConversationMemberPo::getGroupId).toList();
    if (groupIds.isEmpty()) return List.of();
    return mapper.selectList(Wrappers.<ConversationGroupPo>lambdaQuery().in(ConversationGroupPo::getId, groupIds)
        .eq(ConversationGroupPo::getStatus, GroupStatus.ACTIVE.name())).stream().map(this::toPersisted).toList();
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
  public List<Persisted<ConversationGroup>> search(String keyword, long offset, long limit) {
    return mapper.selectPage(new Page<>(offset / limit + 1, limit),
        Wrappers.<ConversationGroupPo>lambdaQuery()
            .like(keyword != null && !keyword.isBlank(), ConversationGroupPo::getName, keyword)
            .orderByDesc(ConversationGroupPo::getCreatedAt))
        .getRecords().stream().map(this::toPersisted).toList();
  }

  @Override
  public long countByKeyword(String keyword) {
    return mapper.selectCount(Wrappers.<ConversationGroupPo>lambdaQuery()
        .like(keyword != null && !keyword.isBlank(), ConversationGroupPo::getName, keyword));
  }

  private Persisted<ConversationGroup> toPersisted(ConversationGroupPo po) {
    return new Persisted<>(fromPo(po), new AuditSnapshot(po.getCreatedAt(), po.getUpdatedAt()));
  }

  private ConversationGroup fromPo(ConversationGroupPo po) {
    return new ConversationGroup(po.getId(), po.getName(), po.getAvatar(), po.getOwnerId(), po.getAnnouncement(),
        po.getMaxMembers(), Boolean.TRUE.equals(po.getAllowMemberInvite()), Boolean.TRUE.equals(po.getAllowMemberFriendRequest()),
        Boolean.TRUE.equals(po.getAllowMemberViewAccount()),
        GroupStatus.valueOf(po.getStatus()), po.getAuthorizationVersion());
  }

  private ConversationGroupPo toPo(ConversationGroup group) {
    ConversationGroupPo po = new ConversationGroupPo();
    po.setId(group.id()); po.setName(group.name()); po.setAvatar(group.avatar()); po.setOwnerId(group.ownerId());
    po.setAnnouncement(group.announcement()); po.setMaxMembers(group.maxMembers()); po.setAllowMemberInvite(group.allowMemberInvite());
    po.setAllowMemberFriendRequest(group.allowMemberFriendRequest());
    po.setAllowMemberViewAccount(group.allowMemberViewAccount());
    po.setStatus(group.status().name());
    po.setAuthorizationVersion(group.authorizationVersion());
    return po;
  }
}
