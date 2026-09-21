package com.gvchat.im.conversation.application.group;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gvchat.common.enums.GroupRole;
import com.gvchat.common.enums.GroupStatus;
import com.gvchat.im.conversation.api.authorization.GroupMessageAuthorizationQuery;
import com.gvchat.im.conversation.api.authorization.GroupMessageAuthorizationSnapshot;
import com.gvchat.im.conversation.domain.group.model.ConversationGroup;
import com.gvchat.im.conversation.domain.group.model.ConversationMember;
import com.gvchat.im.conversation.domain.group.repository.ConversationGroupRepository;
import com.gvchat.im.conversation.domain.group.repository.ConversationMemberRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GroupAuthorizationQueryServiceTest {
  @Test
  void shouldAllowActiveUnmutedMember() {
    GroupMessageAuthorizationSnapshot snapshot = authorize(activeGroup(), activeMember());

    assertTrue(snapshot.allowed());
    assertEquals(null, snapshot.denialCode());
    assertEquals(5L, snapshot.authorizationVersion());
  }

  @Test
  void shouldDenyWhenGroupIsUnavailable() {
    ConversationGroup group = new ConversationGroup(100L, "group", null, 1L, null, 500,
        true, true, true, GroupStatus.DISSOLVED, 3L, LocalDateTime.now(), LocalDateTime.now());

    GroupMessageAuthorizationSnapshot snapshot = authorize(group, activeMember());

    assertFalse(snapshot.allowed());
    assertEquals("GROUP_UNAVAILABLE", snapshot.denialCode());
  }

  @Test
  void shouldDenyNonMember() {
    GroupMessageAuthorizationSnapshot snapshot = authorize(activeGroup(), null);

    assertFalse(snapshot.allowed());
    assertEquals("NOT_GROUP_MEMBER", snapshot.denialCode());
    assertEquals("NOT_MEMBER", snapshot.memberStatus());
  }

  @Test
  void shouldDenyCurrentlyMutedMember() {
    ConversationMember member = new ConversationMember(1L, 100L, 7L, GroupRole.MEMBER, "member", true,
        LocalDateTime.now().plusMinutes(1), 5L, LocalDateTime.now());

    GroupMessageAuthorizationSnapshot snapshot = authorize(activeGroup(), member);

    assertFalse(snapshot.allowed());
    assertEquals("MEMBER_MUTED", snapshot.denialCode());
  }

  private GroupMessageAuthorizationSnapshot authorize(ConversationGroup group, ConversationMember member) {
    GroupAuthorizationQueryService service = new GroupAuthorizationQueryService(
        new StubGroupRepository(group), new StubMemberRepository(member));
    return service.authorize(new GroupMessageAuthorizationQuery(100L, 7L, "command-1",
        Instant.parse("2026-07-24T00:00:00Z")));
  }

  private ConversationGroup activeGroup() {
    return new ConversationGroup(100L, "group", null, 1L, null, 500, true, true, true, GroupStatus.ACTIVE, 3L,
        LocalDateTime.now(), LocalDateTime.now());
  }

  private ConversationMember activeMember() {
    return new ConversationMember(1L, 100L, 7L, GroupRole.MEMBER, "member", false, null, 5L,
        LocalDateTime.now());
  }

  private record StubGroupRepository(ConversationGroup group) implements ConversationGroupRepository {
    @Override public ConversationGroup save(ConversationGroup item) { return item; }
    @Override public Optional<ConversationGroup> findById(long groupId) { return Optional.ofNullable(group); }
    @Override public List<ConversationGroup> findActiveByUserId(long userId) { return List.of(); }
    @Override public long count() { return 0; }
    @Override public long countCreatedSince(LocalDateTime since) { return 0; }
    @Override public List<java.util.Map<String, Object>> dailyCounts(LocalDateTime since) { return List.of(); }
    @Override public List<ConversationGroup> search(String keyword, long offset, long limit) { return List.of(); }
    @Override public long countByKeyword(String keyword) { return 0; }
  }

  private record StubMemberRepository(ConversationMember member) implements ConversationMemberRepository {
    @Override public ConversationMember save(ConversationMember item) { return item; }
    @Override public Optional<ConversationMember> findByGroupIdAndUserId(long groupId, long userId) {
      return Optional.ofNullable(member);
    }
    @Override public List<ConversationMember> findByGroupId(long groupId) { return List.of(); }
    @Override public void delete(long groupId, long userId) { }
  }
}
