package com.gvchat.im.conversation.domain.group.model;

import com.gvchat.common.enums.GroupStatus;
import java.time.LocalDateTime;

public record ConversationGroup(Long id, String name, String avatar, long ownerId, String announcement, int maxMembers,
                                boolean allowMemberInvite, boolean allowMemberFriendRequest, boolean allowMemberViewAccount,
                                GroupStatus status, long authorizationVersion, LocalDateTime createdAt,
                                LocalDateTime updatedAt) {

  public ConversationGroup dissolve(LocalDateTime now) {
    return new ConversationGroup(id, name, avatar, ownerId, announcement, maxMembers, allowMemberInvite,
        allowMemberFriendRequest, allowMemberViewAccount, GroupStatus.DISSOLVED, authorizationVersion + 1, createdAt, now);
  }

  public ConversationGroup update(String newName, String newAvatar, String newAnnouncement, Boolean newAllowMemberInvite,
      Boolean newAllowMemberFriendRequest, Boolean newAllowMemberViewAccount, LocalDateTime now) {
    return new ConversationGroup(id, newName == null ? name : newName, newAvatar == null ? avatar : newAvatar,
        ownerId, newAnnouncement == null ? announcement : newAnnouncement, maxMembers,
        newAllowMemberInvite == null ? allowMemberInvite : newAllowMemberInvite,
        newAllowMemberFriendRequest == null ? allowMemberFriendRequest : newAllowMemberFriendRequest,
        newAllowMemberViewAccount == null ? allowMemberViewAccount : newAllowMemberViewAccount,
        status, authorizationVersion, createdAt, now);
  }

  public ConversationGroup transferOwnership(long newOwnerId, LocalDateTime now) {
    return new ConversationGroup(id, name, avatar, newOwnerId, announcement, maxMembers, allowMemberInvite,
        allowMemberFriendRequest, allowMemberViewAccount, status, authorizationVersion + 1, createdAt, now);
  }

  /**
   * 查看他人「账号」权限（群级隐私开关，由群主控制）：
   * 成员本人与群主始终可见；其余成员只有在「允许群成员查看他人账号」开启时才可见。
   */
  public boolean canViewMemberAccount(long viewerId, long targetUserId) {
    return viewerId == targetUserId || viewerId == ownerId || allowMemberViewAccount;
  }
}
