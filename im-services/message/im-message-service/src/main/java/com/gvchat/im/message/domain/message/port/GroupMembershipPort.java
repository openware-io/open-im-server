package com.gvchat.im.message.domain.message.port;

import java.util.List;

public interface GroupMembershipPort {
  boolean isMember(long groupId, long userId);

  boolean isOwnerOrAdmin(long groupId, long userId);

  List<Long> findMemberUserIds(long groupId);

  default GroupMessageRecipientSnapshot messageRecipientSnapshot(long groupId, long senderId) {
    return new GroupMessageRecipientSnapshot(isMember(groupId, senderId), findMemberUserIds(groupId));
  }

  record GroupMessageRecipientSnapshot(boolean senderAllowed, List<Long> memberUserIds, String denialCode) {
    public GroupMessageRecipientSnapshot(boolean senderAllowed, List<Long> memberUserIds) {
      this(senderAllowed, memberUserIds, null);
    }
  }
}
