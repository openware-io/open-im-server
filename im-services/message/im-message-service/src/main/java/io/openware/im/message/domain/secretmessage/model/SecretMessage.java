package io.openware.im.message.domain.secretmessage.model;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 私密消息聚合根：仅存储客户端密文，服务端不解密；定时销毁由 destroyAt 驱动。
 *
 * <p><b>为什么不带审计列</b>（standards/10 §聚合装配与持久化契约）：
 * {@code createdBy}/{@code createdAt}/{@code updatedBy}/{@code updatedAt} 是记账信息而非业务事实，
 * 由适配器填充；响应与领域规则需要的值经 {@code SecretMessageRepository.Persisted} 的
 * {@code AuditSnapshot} 单独进入上层。领域模型因此不需要理解持久化语义。
 */
public class SecretMessage {
  private final Long id;
  private final Long secretChatId;
  private final String msgId;
  private final Long fromUserId;
  private final String ciphertext;
  private final Long seq;
  private final String status;
  private final LocalDateTime destroyAt;

  private SecretMessage(Long id, Long secretChatId, String msgId, Long fromUserId, String ciphertext, Long seq,
      String status, LocalDateTime destroyAt) {
    this.id = id;
    this.secretChatId = secretChatId;
    this.msgId = msgId;
    this.fromUserId = fromUserId;
    this.ciphertext = ciphertext;
    this.seq = seq;
    this.status = status;
    this.destroyAt = destroyAt;
  }

  public static SecretMessage post(Long secretChatId, String msgId, long fromUserId, String ciphertext, long seq) {
    return new SecretMessage(null, secretChatId, msgId, fromUserId, ciphertext, seq, "active", null);
  }

  /** 由持久化对象重建领域对象（仅适配器使用）。 */
  public static SecretMessage restore(Long id, Long secretChatId, String msgId, Long fromUserId, String ciphertext,
      Long seq, String status, LocalDateTime destroyAt) {
    return new SecretMessage(id, secretChatId, msgId, fromUserId, ciphertext, seq, status, destroyAt);
  }

  /** 接收方已读后计时：设置定时销毁时间；destroyAt 已存在则保持最早值（不可推迟）。 */
  public SecretMessage scheduleDestroy(LocalDateTime destroyAt) {
    LocalDateTime effective = this.destroyAt == null || destroyAt.isBefore(this.destroyAt)
        ? destroyAt
        : this.destroyAt;
    return new SecretMessage(id, secretChatId, msgId, fromUserId, ciphertext, seq, status, effective);
  }

  public boolean isActive() {
    return "active".equals(status);
  }

  /**
   * 按 TTL 推导销毁时刻：{@code deliveredAt + ttl}。
   *
   * <p><b>为什么是静态纯函数而不是实例方法</b>（standards/10 L299：审计字段不属于领域模型）：
   * "销毁时刻 = 投递时刻 + TTL"是私密消息的核心不变量，必须留在领域层（应用服务规则要求核心判断下沉聚合根）；
   * 但它需要读投递时刻，而投递时刻是记账信息、不能存在领域模型上。
   * 把投递时刻作为**参数**传入，两件事同时成立：规则在领域层，字段不在领域模型。
   *
   * <p>调用方从 {@code SecretMessageRepository.Persisted.audit().createdAt()} 取得投递时刻。
   * 这条改造前是 {@code message.getCreatedAt().plus(ttl)} 写在应用服务里——规则错层且字段违规，
   * 现在两处都归位。
   */
  public static LocalDateTime destroyAtFrom(LocalDateTime deliveredAt, Duration ttl) {
    return deliveredAt == null ? null : deliveredAt.plus(ttl);
  }

  public Long getId() { return id; }
  public Long getSecretChatId() { return secretChatId; }
  public String getMsgId() { return msgId; }
  public Long getFromUserId() { return fromUserId; }
  public String getCiphertext() { return ciphertext; }
  public Long getSeq() { return seq; }
  public String getStatus() { return status; }
  public LocalDateTime getDestroyAt() { return destroyAt; }
}
