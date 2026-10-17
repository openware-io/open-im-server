package io.openware.im.conversation.domain.secretgroupchat.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 私密群聊聚合根：承载成员安全码、销毁策略与邀请令牌。
 *
 * <p><b>为什么不带审计列</b>（standards/10 §聚合装配与持久化契约）：{@code createdBy}/{@code createdAt}/
 * {@code updatedBy}/{@code updatedAt} 是记账信息，由适配器在写库时确定；响应需要的值经
 * {@code SecretGroupChatRepository.Persisted} 的 {@code AuditSnapshot} 单独带出。
 * 因此 8 个 {@code with*} 迁移方法与 {@code create} 也**不再接收 {@code operatorId}/{@code occurredAt}**——
 * 审计值在持久化那一刻确定，领域方法不需要认识"操作人"这个概念。
 */
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

  private SecretGroupChat(Long id, Long ownerUserId, String status, String name, String announcement, String safeCode,
      String destroyPolicy, boolean anonymousEnabled, String pinnedMsgId, LocalDateTime pinnedAt, String inviteToken,
      LocalDateTime inviteExpiresAt, boolean ownerOnlyPost) {
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
  }

  public static SecretGroupChat create(long ownerUserId) {
    return new SecretGroupChat(null, ownerUserId, "active", null, null, null, "off", false, null, null, null, null,
        false);
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

  public SecretGroupChat withName(String name) {
    return new SecretGroupChat(id, ownerUserId, status, name, announcement, safeCode, destroyPolicy, anonymousEnabled,
        pinnedMsgId, pinnedAt, inviteToken, inviteExpiresAt, ownerOnlyPost);
  }

  public SecretGroupChat withAnnouncement(String announcement) {
    return new SecretGroupChat(id, ownerUserId, status, name, announcement, safeCode, destroyPolicy, anonymousEnabled,
        pinnedMsgId, pinnedAt, inviteToken, inviteExpiresAt, ownerOnlyPost);
  }

  public SecretGroupChat withSafeCode(String safeCode) {
    return new SecretGroupChat(id, ownerUserId, status, name, announcement, safeCode, destroyPolicy, anonymousEnabled,
        pinnedMsgId, pinnedAt, inviteToken, inviteExpiresAt, ownerOnlyPost);
  }

  public SecretGroupChat withDestroyPolicy(String policy) {
    return new SecretGroupChat(id, ownerUserId, status, name, announcement, safeCode, policy, anonymousEnabled,
        pinnedMsgId, pinnedAt, inviteToken, inviteExpiresAt, ownerOnlyPost);
  }

  public SecretGroupChat withAnonymousEnabled(boolean enabled) {
    return new SecretGroupChat(id, ownerUserId, status, name, announcement, safeCode, destroyPolicy, enabled,
        pinnedMsgId, pinnedAt, inviteToken, inviteExpiresAt, ownerOnlyPost);
  }

  public SecretGroupChat withPinned(String msgId, LocalDateTime pinnedAt) {
    return new SecretGroupChat(id, ownerUserId, status, name, announcement, safeCode, destroyPolicy, anonymousEnabled,
        msgId, pinnedAt, inviteToken, inviteExpiresAt, ownerOnlyPost);
  }

  public SecretGroupChat withInvite(String token, LocalDateTime expiresAt) {
    return new SecretGroupChat(id, ownerUserId, status, name, announcement, safeCode, destroyPolicy, anonymousEnabled,
        pinnedMsgId, pinnedAt, token, expiresAt, ownerOnlyPost);
  }

  public SecretGroupChat withOwnerOnlyPost(boolean enabled) {
    return new SecretGroupChat(id, ownerUserId, status, name, announcement, safeCode, destroyPolicy, anonymousEnabled,
        pinnedMsgId, pinnedAt, inviteToken, inviteExpiresAt, enabled);
  }

  public boolean isActive() {
    return "active".equals(status);
  }

  public boolean isOwner(long userId) {
    return ownerUserId != null && ownerUserId == userId;
  }

  /** 由持久化对象重建领域对象（仅适配器使用）。 */
  public static SecretGroupChat restore(Long id, Long ownerUserId, String status, String name, String announcement,
      String safeCode, String destroyPolicy, boolean anonymousEnabled, String pinnedMsgId, LocalDateTime pinnedAt,
      String inviteToken, LocalDateTime inviteExpiresAt, boolean ownerOnlyPost) {
    return new SecretGroupChat(id, ownerUserId, status, name, announcement, safeCode, destroyPolicy, anonymousEnabled,
        pinnedMsgId, pinnedAt, inviteToken, inviteExpiresAt, ownerOnlyPost);
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
}
