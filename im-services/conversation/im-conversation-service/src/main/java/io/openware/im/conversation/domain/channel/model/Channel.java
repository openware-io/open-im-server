package io.openware.im.conversation.domain.channel.model;

import java.security.SecureRandom;

/**
 * 频道聚合根：单向发布/订阅空间。业务规则（owner 发布权）由领域模型承载。
 *
 * <p><b>为什么不带审计列</b>（standards/10 §聚合装配与持久化契约）：{@code createdBy}/{@code createdAt}/
 * {@code updatedBy}/{@code updatedAt} 是记账信息，由适配器在写库时确定；响应需要的值经
 * {@code ChannelRepository.Persisted} 的 {@code AuditSnapshot} 单独带出。
 * 因此 {@code register}/{@code update} 也**不再接收 {@code operatorId}/{@code occurredAt}**。
 */
public class Channel {
  private final Long id;
  private final String code;
  private final Long ownerId;
  private final String name;
  private final String avatar;
  private final String announcement;
  private final Long discussionGroupId;
  private final String status;

  private Channel(Long id, String code, Long ownerId, String name, String avatar, String announcement,
      Long discussionGroupId, String status) {
    this.id = id;
    this.code = code;
    this.ownerId = ownerId;
    this.name = name;
    this.avatar = avatar;
    this.announcement = announcement;
    this.discussionGroupId = discussionGroupId;
    this.status = status;
  }

  public static Channel register(long ownerId, String name, String avatar, String announcement) {
    return new Channel(null, generateCode(), ownerId, name, avatar, announcement, null, "active");
  }

  /** 由持久化对象重建领域对象（仅适配器使用）。 */
  public static Channel restore(Long id, String code, Long ownerId, String name, String avatar, String announcement,
      Long discussionGroupId, String status) {
    return new Channel(id, code, ownerId, name, avatar, announcement, discussionGroupId, status);
  }

  /** 生成 8 位频道号（大小写无关的 0-9A-Z 去易混淆字符，前缀恒为小写字母，避免纯数字与 id 混淆）。 */
  private static String generateCode() {
    String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 去掉 0/O/1/I
    SecureRandom random = new SecureRandom();
    StringBuilder sb = new StringBuilder("c");
    for (int i = 0; i < 7; i++) {
      sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    return sb.toString();
  }

  /** 仅频道所有者具备发布权。 */
  public boolean isOwnedBy(long userId) {
    return ownerId != null && ownerId == userId;
  }

  /** 更新频道信息（名称/头像/公告），仅 owner 可调用。 */
  public Channel update(String name, String avatar, String announcement) {
    return new Channel(id, code, ownerId, name, avatar, announcement, discussionGroupId, status);
  }

  public Long getId() { return id; }
  public String getCode() { return code; }
  public Long getOwnerId() { return ownerId; }
  public String getName() { return name; }
  public String getAvatar() { return avatar; }
  public String getAnnouncement() { return announcement; }
  public Long getDiscussionGroupId() { return discussionGroupId; }
  public String getStatus() { return status; }
}
