package io.openware.im.conversation.application.group;

import io.openware.common.enums.GroupStatus;
import io.openware.im.conversation.api.authorization.GroupMessageAuthorizationQuery;
import io.openware.im.conversation.api.authorization.GroupMessageAuthorizationSnapshot;
import io.openware.im.conversation.api.authorization.GroupMessageRecipientSnapshot;
import io.openware.im.conversation.api.authorization.ConversationMemberIdsResponse;
import io.openware.im.conversation.domain.group.model.ConversationGroup;
import io.openware.im.conversation.domain.group.model.ConversationMember;
import io.openware.im.conversation.domain.group.repository.ConversationGroupRepository;
import io.openware.im.conversation.domain.group.repository.ConversationMemberRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GroupAuthorizationQueryService {
  private final ConversationGroupRepository groupRepository;
  private final ConversationMemberRepository memberRepository;

  @Transactional(readOnly = true)
  public GroupMessageAuthorizationSnapshot authorize(GroupMessageAuthorizationQuery query) {
    ConversationGroup group = groupRepository.findById(query.conversationId()).orElse(null);
    if (group == null) return denied(query, "GROUP_NOT_FOUND", null, null);
    ConversationMember member = memberRepository.findByGroupIdAndUserId(query.conversationId(), query.userId()).orElse(null);
    if (group.status() != GroupStatus.ACTIVE) return denied(query, "GROUP_UNAVAILABLE", group, member);
    if (member == null) return denied(query, "NOT_GROUP_MEMBER", group, null);
    if (member.muted() && (member.mutedUntil() == null || member.mutedUntil().isAfter(LocalDateTime.now()))) {
      return denied(query, "MEMBER_MUTED", group, member);
    }
    return snapshot(query, group, member, true, null);
  }

  /**
   * 仅校验成员身份，**不考虑群状态**：供聊天记录等只读路径使用。
   * 群解散后成员关系仍保留（参考微信：保留记录、只读、不可再发消息），
   * 若复用发送授权（状态感知）会把历史记录也一并禁掉。
   */
  @Transactional(readOnly = true)
  public boolean isMember(long conversationId, long userId) {
    return memberRepository.findByGroupIdAndUserId(conversationId, userId).isPresent();
  }

  @Transactional(readOnly = true)
  public ConversationMemberIdsResponse memberIds(long conversationId) {
    return new ConversationMemberIdsResponse(conversationId,
        memberRepository.findByGroupId(conversationId).stream().map(ConversationMember::userId).toList());
  }

  @Transactional(readOnly = true)
  public GroupMessageRecipientSnapshot messageRecipientSnapshot(GroupMessageAuthorizationQuery query) {
    GroupMessageAuthorizationSnapshot authorization = authorize(query);
    List<Long> memberUserIds = authorization.allowed()
        ? memberRepository.findByGroupId(query.conversationId()).stream().map(ConversationMember::userId).toList()
        : List.of();
    return new GroupMessageRecipientSnapshot(authorization, memberUserIds);
  }

  private GroupMessageAuthorizationSnapshot denied(GroupMessageAuthorizationQuery query, String code,
      ConversationGroup group, ConversationMember member) {
    return snapshot(query, group, member, false, code);
  }

  private GroupMessageAuthorizationSnapshot snapshot(GroupMessageAuthorizationQuery query, ConversationGroup group,
      ConversationMember member, boolean allowed, String code) {
    long version = Math.max(group == null ? 0 : group.authorizationVersion(), member == null ? 0 : member.authorizationVersion());
    return new GroupMessageAuthorizationSnapshot(query.conversationId(), query.userId(),
        group == null ? "NOT_FOUND" : group.status().name(), member == null ? "NOT_MEMBER" : "ACTIVE",
        member == null ? null : member.role().name(), member == null || member.mutedUntil() == null ? null : member.mutedUntil().toInstant(java.time.ZoneOffset.UTC),
        version, allowed, code);
  }
}
