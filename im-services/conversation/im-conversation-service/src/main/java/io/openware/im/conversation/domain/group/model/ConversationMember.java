package io.openware.im.conversation.domain.group.model;

import io.openware.common.enums.GroupRole;
import java.time.LocalDateTime;

public record ConversationMember(Long id, long groupId, long userId, GroupRole role, String nickname, boolean muted,
                                 LocalDateTime mutedUntil, long authorizationVersion, LocalDateTime joinedAt) {
  public ConversationMember withMute(LocalDateTime until) {
    return new ConversationMember(id, groupId, userId, role, nickname, until != null, until, authorizationVersion + 1,
        joinedAt);
  }

  public ConversationMember withRole(GroupRole newRole) {
    return new ConversationMember(id, groupId, userId, newRole, nickname, muted, mutedUntil, authorizationVersion + 1,
        joinedAt);
  }

  public ConversationMember withNickname(String newNickname) {
    return new ConversationMember(id, groupId, userId, role, newNickname, muted, mutedUntil, authorizationVersion + 1,
        joinedAt);
  }
}
