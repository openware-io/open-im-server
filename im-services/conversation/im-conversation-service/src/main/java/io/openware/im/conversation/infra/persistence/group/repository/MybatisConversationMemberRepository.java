package io.openware.im.conversation.infra.persistence.group.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.common.enums.GroupRole;
import io.openware.im.conversation.domain.group.model.ConversationMember;
import io.openware.im.conversation.domain.group.repository.ConversationMemberRepository;
import io.openware.im.conversation.infra.persistence.group.mapper.ConversationMemberMapper;
import io.openware.im.conversation.infra.persistence.group.po.ConversationMemberPo;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisConversationMemberRepository implements ConversationMemberRepository {
  private final ConversationMemberMapper mapper;

  @Override
  public ConversationMember save(ConversationMember member) {
    ConversationMemberPo po = toPo(member);
    if (po.getId() == null) mapper.insert(po); else mapper.updateById(po);
    return fromPo(po);
  }

  @Override
  public Optional<ConversationMember> findByGroupIdAndUserId(long groupId, long userId) {
    return Optional.ofNullable(mapper.selectOne(Wrappers.<ConversationMemberPo>lambdaQuery()
        .eq(ConversationMemberPo::getGroupId, groupId).eq(ConversationMemberPo::getUserId, userId))).map(this::fromPo);
  }

  @Override
  public List<ConversationMember> findByGroupId(long groupId) {
    return mapper.selectList(Wrappers.<ConversationMemberPo>lambdaQuery().eq(ConversationMemberPo::getGroupId, groupId)
        .orderByAsc(ConversationMemberPo::getJoinedAt)).stream().map(this::fromPo)
        .sorted(Comparator.comparingInt((ConversationMember member) -> roleOrder(member.role()))
            .thenComparing(ConversationMember::joinedAt))
        .toList();
  }

  @Override
  public void delete(long groupId, long userId) {
    mapper.delete(Wrappers.<ConversationMemberPo>lambdaQuery().eq(ConversationMemberPo::getGroupId, groupId)
        .eq(ConversationMemberPo::getUserId, userId));
  }

  private ConversationMember fromPo(ConversationMemberPo po) {
    return new ConversationMember(po.getId(), po.getGroupId(), po.getUserId(), GroupRole.valueOf(po.getRole()), po.getNickname(),
        Boolean.TRUE.equals(po.getMuted()), po.getMutedUntil(), po.getAuthorizationVersion(), po.getJoinedAt());
  }

  private ConversationMemberPo toPo(ConversationMember member) {
    ConversationMemberPo po = new ConversationMemberPo();
    po.setId(member.id()); po.setGroupId(member.groupId()); po.setUserId(member.userId()); po.setRole(member.role().name());
    po.setNickname(member.nickname()); po.setMuted(member.muted()); po.setMutedUntil(member.mutedUntil());
    po.setAuthorizationVersion(member.authorizationVersion()); po.setJoinedAt(member.joinedAt());
    return po;
  }

  private int roleOrder(GroupRole role) {
    return role == GroupRole.OWNER ? 0 : role == GroupRole.ADMIN ? 1 : 2;
  }
}
