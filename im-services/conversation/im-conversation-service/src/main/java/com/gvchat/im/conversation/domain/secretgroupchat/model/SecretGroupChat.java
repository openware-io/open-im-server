package com.gvchat.im.conversation.domain.secretgroupchat.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;

/** 私密群聊聚合根：N 人 E2EE 会话，承载成员管理、握手、销毁策略、匿名发言、置顶消息、邀请链接与发言权限。 */
public class SecretGroupChat {
  private final Long id;
  private final Long ownerUserId;
  private final String status;
  private final String name;
  private final String announcement;
  private final String safeCode;
  private final String destroyPolicy;
  private final boolean anonymousEnabled;
  private final String pinnedMsgId;
  private final LocalDateTime pinnedAt;
  private final String inviteToken;
  private final LocalDateTime inviteExpiresAt;
  private final boolean ownerOnlyPost;
  private final Long createdBy;
  private final LocalDateTime createdAt;
  private final Long updatedBy;
  private final LocalDateTime updatedAt;

  private SecretGroupChat(Long id, Long ownerUserId, String status, String name, String announcement, String safeCode,
      String destroyPolicy, boolean anonymousEnabled, String pinnedMsgId, LocalDateTime pinnedAt, String inviteToken,
      LocalDateTime inviteExpiresAt, boolean ownerOnlyPost, Long createdBy, LocalDateTime createdAt, Long updatedBy,
      LocalDateTime updatedAt) {
    this.id = id;
    this.ownerUserId = ownerUserId;
    this.status = status;
    this.name = name;
    this.announcement = announcement;
    this.safeCode = safeCode;
    this.destroyPolicy = destroyPolicy;
    this.anonymousEnabled = anonymousEnabled;
    this.pinnedMsgId = pinnedMsgId;
    this.pinnedAt = pinnedAt;
    this.inviteToken = inviteToken;
    this.inviteExpiresAt = inviteExpiresAt;
    this.ownerOnlyPost = ownerOnlyPost;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedBy = updatedBy;
    this.updatedAt = updatedAt;
  }

  public static SecretGroupChat create(long ownerUserId, long operatorId, LocalDateTime occurredAt) {
    return new SecretGroupChat(null, ownerUserId, "active", null, null, null, "off", false, null, null, null, null,
        false, operatorId, occurredAt, operatorId, occurredAt);
  }

  /** 群安全码指纹：所有成员公钥排序后拼接，取 SHA-256 前 8 字节十六进制（成员数不足 2 时为 null）。 */
  public static String computeSafeCode(List<String> publicKeys) {
    List<String> sorted = publicKeys.stream()
        .filter(key -> key != null && !key.isBlank())
        .sorted()
        .toList();
    if (sorted.size() < 2) {
      return null;
    }
    String joined = String.join(":", sorted);
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(joined.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 8; i++) {
        sb.append(String.format("%02X", digest[i]));
        if (i < 7) {
          sb.append(" ");
        }
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  public SecretGroupChat withName(String name, long operatorId, LocalDateTime occurredAt) {
    return new SecretGroupChat(id, ownerUserId, status, name, announcement, safeCode, destroyPolicy, anonymousEnabled,
        pinnedMsgId, pinnedAt, inviteToken, inviteExpiresAt, ownerOnlyPost, createdBy, createdAt, operatorId, occurredAt);
  }

  public SecretGroupChat withAnnouncement(String announcement, long operatorId, LocalDateTime occurredAt) {
    return new SecretGroupChat(id, ownerUserId, status, name, announcement, safeCode, destroyPolicy, anonymousEnabled,
        pinnedMsgId, pinnedAt, inviteToken, inviteExpiresAt, ownerOnlyPost, createdBy, createdAt, operatorId, occurredAt);
  }

  public SecretGroupChat withSafeCode(String safeCode, long operatorId, LocalDateTime occurredAt) {
    return new SecretGroupChat(id, ownerUserId, status, name, announcement, safeCode, destroyPolicy, anonymousEnabled,
        pinnedMsgId, pinnedAt, inviteToken, inviteExpiresAt, ownerOnlyPost, createdBy, createdAt, operatorId, occurredAt);
  }

  public SecretGroupChat withDestroyPolicy(String policy, long operatorId, LocalDateTime occurredAt) {
    return new SecretGroupChat(id, ownerUserId, status, name, announcement, safeCode, policy, anonymousEnabled,
        pinnedMsgId, pinnedAt, inviteToken, inviteExpiresAt, ownerOnlyPost, createdBy, createdAt, operatorId, occurredAt);
  }

  public SecretGroupChat withAnonymousEnabled(boolean enabled, long operatorId, LocalDateTime occurredAt) {
    return new SecretGroupChat(id, ownerUserId, status, name, announcement, safeCode, destroyPolicy, enabled,
        pinnedMsgId, pinnedAt, inviteToken, inviteExpiresAt, ownerOnlyPost, createdBy, createdAt, operatorId, occurredAt);
  }

  public SecretGroupChat withPinned(String msgId, LocalDateTime pinnedAt, long operatorId, LocalDateTime occurredAt) {
    return new SecretGroupChat(id, ownerUserId, status, name, announcement, safeCode, destroyPolicy, anonymousEnabled,
        msgId, pinnedAt, inviteToken, inviteExpiresAt, ownerOnlyPost, createdBy, createdAt, operatorId, occurredAt);
  }

  public SecretGroupChat withInvite(String token, LocalDateTime expiresAt, long operatorId, LocalDateTime occurredAt) {
    return new SecretGroupChat(id, ownerUserId, status, name, announcement, safeCode, destroyPolicy, anonymousEnabled,
        pinnedMsgId, pinnedAt, token, expiresAt, ownerOnlyPost, createdBy, createdAt, operatorId, occurredAt);
  }

  public SecretGroupChat withOwnerOnlyPost(boolean enabled, long operatorId, LocalDateTime occurredAt) {
    return new SecretGroupChat(id, ownerUserId, status, name, announcement, safeCode, destroyPolicy, anonymousEnabled,
        pinnedMsgId, pinnedAt, inviteToken, inviteExpiresAt, enabled, createdBy, createdAt, operatorId, occurredAt);
  }

  public boolean isActive() {
    return "active".equals(status);
  }

  public boolean isOwner(long userId) {
    return ownerUserId != null && ownerUserId == userId;
  }

  public static SecretGroupChat restore(Long id, Long ownerUserId, String status, String name, String announcement,
      String safeCode, String destroyPolicy, boolean anonymousEnabled, String pinnedMsgId, LocalDateTime pinnedAt,
      String inviteToken, LocalDateTime inviteExpiresAt, boolean ownerOnlyPost, Long createdBy, LocalDateTime createdAt,
      Long updatedBy, LocalDateTime updatedAt) {
    return new SecretGroupChat(id, ownerUserId, status, name, announcement, safeCode, destroyPolicy, anonymousEnabled,
        pinnedMsgId, pinnedAt, inviteToken, inviteExpiresAt, ownerOnlyPost, createdBy, createdAt, updatedBy, updatedAt);
  }

  public Long getId() { return id; }
  public Long getOwnerUserId() { return ownerUserId; }
  public String getStatus() { return status; }
  public String getName() { return name; }
  public String getAnnouncement() { return announcement; }
  public String getSafeCode() { return safeCode; }
  public String getDestroyPolicy() { return destroyPolicy; }
  public boolean isAnonymousEnabled() { return anonymousEnabled; }
  public String getPinnedMsgId() { return pinnedMsgId; }
  public LocalDateTime getPinnedAt() { return pinnedAt; }
  public String getInviteToken() { return inviteToken; }
  public LocalDateTime getInviteExpiresAt() { return inviteExpiresAt; }
  public boolean isOwnerOnlyPost() { return ownerOnlyPost; }
  public Long getCreatedBy() { return createdBy; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public Long getUpdatedBy() { return updatedBy; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
}
