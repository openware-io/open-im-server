package io.openware.im.conversation.api.secretgroupchat;

import java.time.LocalDateTime;
import java.util.List;

public record SecretGroupChatResult(Long id, Long ownerUserId, String status, String name, String announcement,
    String safeCode, String destroyPolicy, boolean anonymousEnabled, String pinnedMsgId, LocalDateTime pinnedAt,
    String inviteToken, LocalDateTime inviteExpiresAt, boolean ownerOnlyPost, List<MemberResult> members, Long createdBy,
    LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {

  public record MemberResult(Long userId, String devicePublicKey, LocalDateTime joinedAt) {
  }
}
