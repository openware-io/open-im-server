package com.gvchat.im.conversation.application.group;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.common.enums.GroupRole;
import com.gvchat.common.enums.GroupStatus;
import com.gvchat.common.exception.ApiException;
import com.gvchat.im.conversation.api.group.AddGroupMembersRequest;
import com.gvchat.im.conversation.api.group.UpdateGroupRequest;
import com.gvchat.im.conversation.domain.group.model.ConversationGroup;
import com.gvchat.im.conversation.domain.group.model.ConversationMember;
import com.gvchat.im.conversation.domain.group.repository.ConversationGroupRepository;
import com.gvchat.im.conversation.domain.group.repository.ConversationMemberRepository;
import com.gvchat.im.conversation.domain.group.repository.ConversationOutboxRepository;
import com.gvchat.im.conversation.domain.group.port.ActiveUserPort;
import com.gvchat.im.conversation.domain.group.port.FeatureTogglePort;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GroupApplicationServiceTest {
  @Test
  void shouldAllowMemberInvitationByDefault() {
    Fixture fixture = fixture(true, owner(), member(2L, GroupRole.MEMBER, 1));

    fixture.service.addMembers(100L, 2L, new AddGroupMembersRequest(List.of(3L)));

    assertEquals(3, fixture.members.members.size());
  }

  @Test
  void shouldRejectMemberInvitationWhenDisabled() {
    Fixture fixture = fixture(false, owner(), member(2L, GroupRole.MEMBER, 1));

    assertThrows(ApiException.class,
        () -> fixture.service.addMembers(100L, 2L, new AddGroupMembersRequest(List.of(3L))));
  }

  @Test
  void shouldRejectInvitingInactiveOrMissingUsers() {
    InMemoryGroupRepository groups = new InMemoryGroupRepository(true);
    InMemoryMemberRepository members = new InMemoryMemberRepository(List.of(owner(), member(2L, GroupRole.MEMBER, 1)));
    GroupApplicationService service = new GroupApplicationService(groups, members, new NoopOutboxRepository(),
        userIds -> userIds.stream().filter(userId -> userId != 3L).toList(), userIds -> java.util.Map.of(),
        featureTogglePort(), new ObjectMapper().findAndRegisterModules());

    assertThrows(ApiException.class, () -> service.addMembers(100L, 2L, new AddGroupMembersRequest(List.of(3L))));
    assertEquals(2, members.members.size());
  }

  @Test
  void shouldReturnCurrentUserAvatarForGroupMembers() {
    InMemoryGroupRepository groups = new InMemoryGroupRepository(true);
    InMemoryMemberRepository members = new InMemoryMemberRepository(List.of(owner(), member(2L, GroupRole.MEMBER, 1)));
    GroupApplicationService service = new GroupApplicationService(groups, members, new NoopOutboxRepository(),
        userIds -> userIds, userIds -> java.util.Map.of(2L,
            new com.gvchat.im.conversation.domain.group.port.UserProfilePort.UserProfile("member", "Member", "avatar-url")),
        featureTogglePort(), new ObjectMapper().findAndRegisterModules());

    var response = service.getGroupMembers(100L, 1L);

    assertEquals("avatar-url", response.stream().filter(member -> member.userId() == 2L).findFirst().orElseThrow().avatar());
  }

  @Test
  void shouldAllowAdminToUpdateMemberInvitationSetting() {
    Fixture fixture = fixture(true, owner(), member(2L, GroupRole.ADMIN, 1));

    fixture.service.updateGroup(100L, 2L, new UpdateGroupRequest(null, null, null, false, null, null));

    assertFalse(fixture.groups.group.allowMemberInvite());
  }

  @Test
  void shouldAllowOwnerToUpdateViewAccountSetting() {
    Fixture fixture = fixture(true, owner(), member(2L, GroupRole.MEMBER, 1));

    fixture.service.updateGroup(100L, 1L, new UpdateGroupRequest(null, null, null, null, null, false));

    assertFalse(fixture.groups.group.allowMemberViewAccount());
  }

  @Test
  void shouldHideMemberAccountForNonOwnerWhenViewAccountDisabled() {
    InMemoryGroupRepository groups = new InMemoryGroupRepository(true, false);
    InMemoryMemberRepository members = new InMemoryMemberRepository(
        List.of(owner(), member(2L, GroupRole.MEMBER, 1), member(3L, GroupRole.MEMBER, 2)));
    GroupApplicationService service = new GroupApplicationService(groups, members, new NoopOutboxRepository(),
        userIds -> userIds, userIds -> java.util.Map.of(
            2L, new com.gvchat.im.conversation.domain.group.port.UserProfilePort.UserProfile("member", "Member", "avatar-2"),
            3L, new com.gvchat.im.conversation.domain.group.port.UserProfilePort.UserProfile("viewer", "Viewer", "avatar-3")),
        featureTogglePort(), new ObjectMapper().findAndRegisterModules());

    var response = service.getGroupMembers(100L, 3L);

    assertEquals(null, response.stream().filter(m -> m.userId() == 2L).findFirst().orElseThrow().username());
    assertEquals("viewer", response.stream().filter(m -> m.userId() == 3L).findFirst().orElseThrow().username());
  }

  @Test
  void shouldShowMemberAccountForOwnerWhenViewAccountDisabled() {
    InMemoryGroupRepository groups = new InMemoryGroupRepository(true, false);
    InMemoryMemberRepository members = new InMemoryMemberRepository(List.of(owner(), member(2L, GroupRole.MEMBER, 1)));
    GroupApplicationService service = new GroupApplicationService(groups, members, new NoopOutboxRepository(),
        userIds -> userIds, userIds -> java.util.Map.of(2L,
            new com.gvchat.im.conversation.domain.group.port.UserProfilePort.UserProfile("member", "Member", "avatar-url")),
        featureTogglePort(), new ObjectMapper().findAndRegisterModules());

    var response = service.getGroupMembers(100L, 1L);

    assertEquals("member", response.stream().filter(m -> m.userId() == 2L).findFirst().orElseThrow().username());
  }

  @Test
  void shouldShowMemberAccountWhenViewAccountEnabled() {
    InMemoryGroupRepository groups = new InMemoryGroupRepository(true, true);
    InMemoryMemberRepository members = new InMemoryMemberRepository(
        List.of(owner(), member(2L, GroupRole.MEMBER, 1), member(3L, GroupRole.MEMBER, 2)));
    GroupApplicationService service = new GroupApplicationService(groups, members, new NoopOutboxRepository(),
        userIds -> userIds, userIds -> java.util.Map.of(2L,
            new com.gvchat.im.conversation.domain.group.port.UserProfilePort.UserProfile("member", "Member", "avatar-url")),
        featureTogglePort(), new ObjectMapper().findAndRegisterModules());

    var response = service.getGroupMembers(100L, 3L);

    assertEquals("member", response.stream().filter(m -> m.userId() == 2L).findFirst().orElseThrow().username());
  }

  @Test
  void shouldTransferOwnershipToEarliestAdminWhenOwnerLeaves() {
    Fixture fixture = fixture(true, owner(), member(2L, GroupRole.MEMBER, 1), member(3L, GroupRole.ADMIN, 2),
        member(4L, GroupRole.ADMIN, 3));

    fixture.service.leaveGroup(100L, 1L);

    assertEquals(3L, fixture.groups.group.ownerId());
    assertEquals(GroupRole.OWNER, fixture.members.findByGroupIdAndUserId(100L, 3L).orElseThrow().role());
    assertEquals(Optional.empty(), fixture.members.findByGroupIdAndUserId(100L, 1L));
  }

  @Test
  void shouldTransferOwnershipToEarliestMemberWithoutAdmin() {
    Fixture fixture = fixture(true, owner(), member(2L, GroupRole.MEMBER, 1), member(3L, GroupRole.MEMBER, 2));

    fixture.service.leaveGroup(100L, 1L);

    assertEquals(2L, fixture.groups.group.ownerId());
    assertEquals(GroupRole.OWNER, fixture.members.findByGroupIdAndUserId(100L, 2L).orElseThrow().role());
  }

  @Test
  void shouldDissolveEmptyGroupWhenOwnerLeaves() {
    Fixture fixture = fixture(true, owner());

    fixture.service.leaveGroup(100L, 1L);

    assertEquals(GroupStatus.DISSOLVED, fixture.groups.group.status());
    assertEquals(0, fixture.members.members.size());
  }

  private Fixture fixture(boolean allowMemberInvite, ConversationMember... members) {
    InMemoryGroupRepository groups = new InMemoryGroupRepository(allowMemberInvite);
    InMemoryMemberRepository memberRepository = new InMemoryMemberRepository(List.of(members));
    ActiveUserPort activeUsers = userIds -> userIds;
    GroupApplicationService service = new GroupApplicationService(groups, memberRepository, new NoopOutboxRepository(),
        activeUsers, userIds -> java.util.Map.of(), featureTogglePort(), new ObjectMapper().findAndRegisterModules());
    return new Fixture(service, groups, memberRepository);
  }

  private static FeatureTogglePort featureTogglePort() {
    return () -> true;
  }

  private ConversationMember owner() { return member(1L, GroupRole.OWNER, 0); }

  private ConversationMember member(long userId, GroupRole role, int joinedOffset) {
    return new ConversationMember(userId, 100L, userId, role, "", false, null, 1,
        LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(joinedOffset));
  }

  private record Fixture(GroupApplicationService service, InMemoryGroupRepository groups,
                         InMemoryMemberRepository members) { }

  private static final class InMemoryGroupRepository implements ConversationGroupRepository {
    private ConversationGroup group;

    private InMemoryGroupRepository(boolean allowMemberInvite) {
      this(allowMemberInvite, true);
    }

    private InMemoryGroupRepository(boolean allowMemberInvite, boolean allowMemberViewAccount) {
      LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);
      group = new ConversationGroup(100L, "group", null, 1L, "", 500, allowMemberInvite, true,
          allowMemberViewAccount, GroupStatus.ACTIVE, 1, now, now);
    }

    @Override public ConversationGroup save(ConversationGroup item) { return group = item; }
    @Override public Optional<ConversationGroup> findById(long groupId) { return Optional.of(group); }
    @Override public List<ConversationGroup> findActiveByUserId(long userId) { return List.of(); }
    @Override public long count() { return 1; }
    @Override public long countCreatedSince(LocalDateTime since) { return 0; }
    @Override public List<java.util.Map<String, Object>> dailyCounts(LocalDateTime since) { return List.of(); }
    @Override public List<ConversationGroup> search(String keyword, long offset, long limit) { return List.of(); }
    @Override public long countByKeyword(String keyword) { return 0; }
  }

  private static final class InMemoryMemberRepository implements ConversationMemberRepository {
    private final List<ConversationMember> members;

    private InMemoryMemberRepository(List<ConversationMember> members) { this.members = new ArrayList<>(members); }

    @Override public ConversationMember save(ConversationMember member) {
      delete(member.groupId(), member.userId());
      members.add(member);
      return member;
    }

    @Override public Optional<ConversationMember> findByGroupIdAndUserId(long groupId, long userId) {
      return members.stream().filter(member -> member.groupId() == groupId && member.userId() == userId).findFirst();
    }

    @Override public List<ConversationMember> findByGroupId(long groupId) {
      return members.stream().filter(member -> member.groupId() == groupId).toList();
    }

    @Override public void delete(long groupId, long userId) {
      members.removeIf(member -> member.groupId() == groupId && member.userId() == userId);
    }
  }

  private static final class NoopOutboxRepository implements ConversationOutboxRepository {
    @Override public void append(String eventId, String aggregateId, String topic, String shardingKey, String payloadJson) { }
    @Override public List<PendingEvent> findPending(int limit) { return List.of(); }
    @Override public void markPublished(long id) { }
  }
}
