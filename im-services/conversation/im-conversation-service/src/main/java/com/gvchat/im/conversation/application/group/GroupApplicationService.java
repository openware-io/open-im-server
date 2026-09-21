package com.gvchat.im.conversation.application.group;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.common.enums.GroupRole;
import com.gvchat.common.enums.GroupStatus;
import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.im.conversation.api.group.AddGroupMembersRequest;
import com.gvchat.im.conversation.api.group.CreateGroupRequest;
import com.gvchat.im.conversation.api.group.MuteGroupMemberRequest;
import com.gvchat.im.conversation.api.group.SetGroupMemberRoleRequest;
import com.gvchat.im.conversation.api.group.UpdateGroupRequest;
import com.gvchat.im.conversation.api.group.GroupMemberResponse;
import com.gvchat.im.conversation.domain.group.model.ConversationGroup;
import com.gvchat.im.conversation.domain.group.model.ConversationMember;
import com.gvchat.im.conversation.domain.group.repository.ConversationGroupRepository;
import com.gvchat.im.conversation.domain.group.repository.ConversationMemberRepository;
import com.gvchat.im.conversation.domain.group.repository.ConversationOutboxRepository;
import com.gvchat.im.conversation.domain.group.port.ActiveUserPort;
import com.gvchat.im.conversation.domain.group.port.FeatureTogglePort;
import com.gvchat.im.conversation.domain.group.port.UserProfilePort;
import com.gvchat.im.conversation.api.authorization.ConversationAuthorizationChangedEvent;
import com.gvchat.protocol.mq.command.MessageSendCommand;
import com.gvchat.protocol.mq.support.ConversationIds;
import com.gvchat.protocol.mq.topic.ImMqTopics;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GroupApplicationService {
  private final ConversationGroupRepository groupRepository;
  private final ConversationMemberRepository memberRepository;
  private final ConversationOutboxRepository outboxRepository;
  private final ActiveUserPort activeUserPort;
  private final UserProfilePort userProfilePort;
  private final FeatureTogglePort featureTogglePort;
  private final ObjectMapper objectMapper;

  @Transactional
  public ConversationGroup createGroup(long ownerId, CreateGroupRequest command) {
    if (!featureTogglePort.isGroupChatEnabled()) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Group chat is disabled");
    }
    assertAllUsersActive(createMemberIds(ownerId, command.memberIds()));
    LocalDateTime now = LocalDateTime.now();
    ConversationGroup group = groupRepository.save(new ConversationGroup(null, command.name(), command.avatar(), ownerId,
        "", 500, true, true, true, GroupStatus.ACTIVE, 1, now, now));
    memberRepository.save(new ConversationMember(null, group.id(), ownerId, GroupRole.OWNER, "", false, null, 1, now));
    publish(group, new ConversationMember(null, group.id(), ownerId, GroupRole.OWNER, "", false, null, 1, now), "CREATED");
    if (command.memberIds() != null) command.memberIds().stream().filter(java.util.Objects::nonNull).filter(id -> id > 0)
        .filter(id -> id != ownerId).distinct().forEach(id -> {
      ConversationMember member = memberRepository.save(new ConversationMember(null, group.id(), id, GroupRole.MEMBER, "", false, null, 1, now));
      publish(group, member, "MEMBER_ADDED");
    });
    return group;
  }

  @Transactional(readOnly = true)
  public List<ConversationGroup> getUserGroups(long userId) { return groupRepository.findActiveByUserId(userId); }

  @Transactional(readOnly = true)
  public ConversationGroup getGroupInfo(long groupId, long userId) {
    member(groupId, userId);
    // 群解散后仍要能查看群信息与聊天记录（参考微信：**保留记录、只读、不可再发消息**）。
    // 此前这里复用了带 ACTIVE 过滤的 group(id)，解散后直接 404，历史记录也一并看不到。
    // 写路径（改名/踢人/禁言/角色/解散）继续使用带 ACTIVE 过滤的 group(id)。
    return groupRepository.findById(groupId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Group not found"));
  }

  @Transactional(readOnly = true)
  public List<GroupMemberResponse> getGroupMembers(long groupId, long userId) {
    member(groupId, userId);
    ConversationGroup group = groupRepository.findById(groupId).orElse(null);
    List<ConversationMember> members = memberRepository.findByGroupId(groupId);
    Map<Long, UserProfilePort.UserProfile> profiles = userProfilePort.findByUserIds(
        members.stream().map(ConversationMember::userId).toList());
    return members.stream().map(value -> {
      UserProfilePort.UserProfile profile = profiles.get(value.userId());
      String username = profile == null ? null : profile.username();
      // 群级隐私：非群主且「允许群成员查看他人账号」关闭时，对其他成员隐藏「账号」；本人与群主始终可见。
      if (group != null && !group.canViewMemberAccount(userId, value.userId())) {
        username = null;
      }
      return new GroupMemberResponse(value.userId(), value.nickname(), username,
          value.role().name(), profile == null ? null : profile.avatar());
    }).toList();
  }

  @Transactional(readOnly = true)
  public GroupStats stats(int days) {
    LocalDateTime since = days <= 0 ? LocalDate.now(Clock.systemUTC()).atStartOfDay()
        : LocalDateTime.now(Clock.systemUTC()).minusDays(days);
    return new GroupStats(groupRepository.count(), groupRepository.countCreatedSince(since),
        groupRepository.dailyCounts(since));
  }

  @Transactional
  public ConversationGroup updateGroup(long groupId, long userId, UpdateGroupRequest command) {
    if (member(groupId, userId).role() == GroupRole.MEMBER) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Only admin can update group");
    }
    ConversationGroup updated = groupRepository.save(group(groupId).update(command.name(), command.avatar(), command.announcement(),
        command.allowMemberInvite(), command.allowMemberFriendRequest(), command.allowMemberViewAccount(), LocalDateTime.now()));
    publish(updated, member(groupId, userId), "GROUP_UPDATED");
    return updated;
  }

  @Transactional
  public Map<String, Integer> addMembers(long groupId, long userId, AddGroupMembersRequest command) {
    ConversationMember operator = member(groupId, userId);
    ConversationGroup group = groupRepository.findByIdForUpdate(groupId).filter(value -> value.status() == GroupStatus.ACTIVE)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Group not found"));
    if (operator.role() == GroupRole.MEMBER && !group.allowMemberInvite()) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Member invitations are disabled");
    }
    List<Long> ids = command.userIds().stream().filter(java.util.Objects::nonNull).filter(id -> id > 0).distinct()
        .filter(id -> memberRepository.findByGroupIdAndUserId(groupId, id).isEmpty()).toList();
    assertAllUsersActive(ids);
    if (memberRepository.findByGroupId(groupId).size() + ids.size() > group.maxMembers()) throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Group capacity exceeded");
    LocalDateTime now = LocalDateTime.now();
    ids.forEach(id -> {
      ConversationMember member = memberRepository.save(new ConversationMember(null, groupId, id, GroupRole.MEMBER, "", false, null, 1, now));
      publish(group, member, "MEMBER_ADDED");
      // 群操作系统消息：XX 邀请 YY 加入了群聊。
      publishSystemMessage(groupId, inviteText(operator, id));
    });
    return Map.of("added", ids.size());
  }

  @Transactional
  public void removeMember(long groupId, long operatorId, long targetUserId) {
    ConversationMember operator = member(groupId, operatorId);
    ConversationMember target = member(groupId, targetUserId);
    if (operatorId == targetUserId || rank(operator.role()) <= rank(target.role())) throw new ApiException(HttpStatusCodes.FORBIDDEN, "Cannot remove member");
    memberRepository.delete(groupId, targetUserId);
    publish(group(groupId), target, "MEMBER_REMOVED");
  }

  @Transactional
  public void leaveGroup(long groupId, long userId) {
    ConversationMember leavingMember = member(groupId, userId);
    ConversationGroup group = group(groupId);
    if (leavingMember.role() != GroupRole.OWNER) {
      memberRepository.delete(groupId, userId);
      publish(group, leavingMember, "MEMBER_LEFT");
      return;
    }

    ConversationMember successor = memberRepository.findByGroupId(groupId).stream()
        .filter(candidate -> candidate.userId() != userId)
        .sorted(java.util.Comparator.comparingInt((ConversationMember candidate) -> candidate.role() == GroupRole.ADMIN ? 0 : 1)
            .thenComparing(ConversationMember::joinedAt))
        .findFirst().orElse(null);
    if (successor == null) {
      ConversationGroup dissolved = groupRepository.save(group.dissolve(LocalDateTime.now()));
      memberRepository.delete(groupId, userId);
      publish(dissolved, leavingMember, "GROUP_DISSOLVED");
      return;
    }

    ConversationMember newOwner = memberRepository.save(successor.withRole(GroupRole.OWNER));
    ConversationGroup transferred = groupRepository.save(group.transferOwnership(newOwner.userId(), LocalDateTime.now()));
    memberRepository.delete(groupId, userId);
    publish(transferred, newOwner, "OWNER_TRANSFERRED");
    publish(transferred, leavingMember, "MEMBER_LEFT");
  }

  /** 成员设置自己在群内的昵称（空串表示清空，回退全局昵称）。 */
  @Transactional
  public ConversationMember updateMyNickname(long groupId, long userId, String nickname) {
    ConversationMember current = member(groupId, userId);
    String trimmed = nickname == null ? "" : nickname.trim();
    if (trimmed.length() > 64) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Nickname too long");
    }
    ConversationMember updated = memberRepository.save(current.withNickname(trimmed));
    return updated;
  }

  @Transactional
  public ConversationMember muteMember(long groupId, long operatorId, MuteGroupMemberRequest command) {
    ConversationMember operator = member(groupId, operatorId);
    if (operator.role() == GroupRole.MEMBER) throw new ApiException(HttpStatusCodes.FORBIDDEN, "Only admin can mute");
    ConversationMember target = member(groupId, command.userId());
    if (operatorId == target.userId() || rank(operator.role()) <= rank(target.role())) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Cannot mute member with equal or higher role");
    }
    ConversationMember updated = memberRepository.save(target.withMute(command.duration() != null && command.duration() > 0
        ? LocalDateTime.now().plusMinutes(command.duration()) : null));
    publish(group(groupId), updated, "MEMBER_MUTE_CHANGED");
    return updated;
  }

  @Transactional
  public ConversationMember setRole(long groupId, long operatorId, SetGroupMemberRoleRequest command) {
    if (member(groupId, operatorId).role() != GroupRole.OWNER) throw new ApiException(HttpStatusCodes.FORBIDDEN, "Only owner can set role");
    ConversationMember target = member(groupId, command.userId());
    GroupRole role = GroupRole.fromValue(command.role());
    if (target.role() == GroupRole.OWNER || role == GroupRole.OWNER) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Use leave group to transfer ownership");
    }
    ConversationMember updated = memberRepository.save(target.withRole(role));
    publish(group(groupId), updated, "MEMBER_ROLE_CHANGED");
    return updated;
  }

  @Transactional
  public void dissolveGroup(long groupId, long userId) {
    ConversationGroup group = group(groupId);
    if (group.ownerId() != userId) throw new ApiException(HttpStatusCodes.FORBIDDEN, "Only owner can dissolve");
    ConversationGroup dissolved = groupRepository.save(group.dissolve(LocalDateTime.now()));
    memberRepository.findByGroupId(groupId).forEach(member -> publish(dissolved, member, "GROUP_DISSOLVED"));
  }

  @Transactional
  public void adminDissolveGroup(long groupId) {
    ConversationGroup group = group(groupId);
    ConversationGroup dissolved = groupRepository.save(group.dissolve(LocalDateTime.now()));
    memberRepository.findByGroupId(groupId).forEach(member -> publish(dissolved, member, "GROUP_DISSOLVED"));
  }

  /** 管理端分页检索群组（只读查询，供 /internal/admin/groups 使用）。 */
  @Transactional(readOnly = true)
  public Map<String, Object> listAdminGroups(String keyword, int page, int pageSize) {
    int offset = (page - 1) * pageSize;
    List<Map<String, Object>> items = groupRepository.search(keyword, offset, pageSize).stream()
        .map(GroupApplicationService::groupAdminView).toList();
    return Map.of("items", items, "page", page, "pageSize", pageSize,
        "total", groupRepository.countByKeyword(keyword), "updatedAt", java.time.Instant.now().toString());
  }

  /** 管理端查询群成员列表（只读）。 */
  @Transactional(readOnly = true)
  public List<Map<String, Object>> listGroupMembers(long groupId) {
    return memberRepository.findByGroupId(groupId).stream().map(GroupApplicationService::memberAdminView).toList();
  }

  /** 群级隐私：是否允许群成员互加好友（供用户服务好友申请校验，群不存在时按允许处理）。 */
  @Transactional(readOnly = true)
  public boolean isMemberFriendRequestAllowed(long groupId) {
    return groupRepository.findById(groupId).map(ConversationGroup::allowMemberFriendRequest).orElse(true);
  }

  private static Map<String, Object> groupAdminView(ConversationGroup group) {
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("id", group.id()); result.put("name", group.name()); result.put("ownerId", group.ownerId());
    result.put("status", group.status().name()); result.put("createdAt", group.createdAt()); result.put("updatedAt", group.updatedAt());
    return result;
  }

  private static Map<String, Object> memberAdminView(ConversationMember member) {
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("id", member.id()); result.put("groupId", member.groupId()); result.put("userId", member.userId());
    result.put("role", member.role().name()); result.put("nickname", member.nickname()); result.put("muted", member.muted());
    result.put("joinedAt", member.joinedAt());
    return result;
  }

  private ConversationGroup group(long id) {
    return groupRepository.findById(id).filter(value -> value.status() == GroupStatus.ACTIVE)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Group not found"));
  }

  private ConversationMember member(long groupId, long userId) {
    return memberRepository.findByGroupIdAndUserId(groupId, userId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.FORBIDDEN, "Not a group member"));
  }

  private int rank(GroupRole role) { return role == GroupRole.OWNER ? 3 : role == GroupRole.ADMIN ? 2 : 1; }

  private List<Long> createMemberIds(long ownerId, List<Long> memberIds) {
    java.util.stream.Stream<Long> candidates = memberIds == null ? java.util.stream.Stream.empty() : memberIds.stream();
    return java.util.stream.Stream.concat(java.util.stream.Stream.of(ownerId), candidates).filter(java.util.Objects::nonNull)
        .filter(id -> id > 0).distinct().toList();
  }

  private void assertAllUsersActive(List<Long> userIds) {
    if (userIds.isEmpty()) return;
    java.util.Set<Long> activeUserIds = new java.util.HashSet<>(activeUserPort.findActiveUserIds(userIds));
    List<Long> inactiveOrMissing = userIds.stream().filter(id -> !activeUserIds.contains(id)).toList();
    if (!inactiveOrMissing.isEmpty()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Group members must be active users: " + inactiveOrMissing);
    }
  }

  /** 群操作系统消息：以 msgType=system 落库到消息服务（发送方为用户 0）。 */
  private void publishSystemMessage(long groupId, String content) {
    try {
      String eventId = UUID.randomUUID().toString();
      MessageSendCommand command = MessageSendCommand.builder()
          .commandId(eventId)
          .conversationId(ConversationIds.groupConversation(groupId))
          .senderId(0L)
          .senderUsername("")
          .chatType("group")
          .toId(String.valueOf(groupId))
          .msgType("system")
          .content(content)
          .atUsersJson("[]")
          .mediaObjectIds(List.of())
          .acceptedAt(java.time.Instant.now(Clock.systemUTC()))
          .build();
      outboxRepository.append(eventId, "group:" + groupId, ImMqTopics.MESSAGE_SEND_COMMAND,
          String.valueOf(groupId), objectMapper.writeValueAsString(command));
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalStateException("Unable to serialize group system message", ex);
    }
  }

  /** 「XX 邀请 YY 加入了群聊」文案：优先全局昵称，其次用户名，兜底「用户{id}」。 */
  private String inviteText(ConversationMember operator, long addedUserId) {
    Map<Long, UserProfilePort.UserProfile> profiles = userProfilePort.findByUserIds(
        java.util.List.of(operator.userId(), addedUserId));
    return displayName(profiles.get(operator.userId()), operator.userId())
        + " 邀请 " + displayName(profiles.get(addedUserId), addedUserId) + " 加入了群聊";
  }

  private String displayName(UserProfilePort.UserProfile profile, long userId) {
    if (profile != null && profile.nickname() != null && !profile.nickname().isBlank()) {
      return profile.nickname();
    }
    if (profile != null && profile.username() != null && !profile.username().isBlank()) {
      return profile.username();
    }
    return "用户" + userId;
  }

  private void publish(ConversationGroup group, ConversationMember member, String changeType) {
    java.util.Set<Long> recipientUserIds = new java.util.LinkedHashSet<>();
    memberRepository.findByGroupId(group.id()).forEach(value -> recipientUserIds.add(value.userId()));
    recipientUserIds.add(member.userId());
    for (Long recipientUserId : recipientUserIds) {
      publishToRecipient(group, member, recipientUserId, changeType);
    }
  }

  private void publishToRecipient(ConversationGroup group, ConversationMember member, long recipientUserId, String changeType) {
    try {
      String eventId = UUID.randomUUID().toString();
      String payload = objectMapper.writeValueAsString(new ConversationAuthorizationChangedEvent(eventId, 1,
          java.time.Instant.now(), group.id(), recipientUserId, member.userId(), changeType, group.status().name(), "ACTIVE",
          member.role().name(), member.mutedUntil() == null ? null : member.mutedUntil().toInstant(java.time.ZoneOffset.UTC),
          Math.max(group.authorizationVersion(), member.authorizationVersion())));
      outboxRepository.append(eventId, String.valueOf(group.id()), ImMqTopics.CONVERSATION_AUTHORIZATION_CHANGED_EVENT,
          String.valueOf(group.id()), payload);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalStateException("Unable to serialize conversation authorization event", ex);
    }
  }

  public record GroupStats(long totalGroups, long recentGroups, List<Map<String, Object>> dailySeries) {
  }
}
