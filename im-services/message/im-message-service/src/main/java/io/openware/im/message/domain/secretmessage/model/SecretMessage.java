package io.openware.im.message.domain.secretmessage.model;

import java.time.LocalDateTime;

/** 私密消息聚合根：仅存储客户端密文，服务端不解密；定时销毁由 destroyAt 驱动。 */
public class SecretMessage {
  private final Long id;
  private final Long secretChatId;
  private final String msgId;
  private final Long fromUserId;
  private final String ciphertext;
  private final Long seq;
  private final String status;
  private final LocalDateTime destroyAt;
  private final Long createdBy;
  private final LocalDateTime createdAt;
  private final Long updatedBy;
  private final LocalDateTime updatedAt;

  private SecretMessage(Long id, Long secretChatId, String msgId, Long fromUserId, String ciphertext, Long seq,
      String status, LocalDateTime destroyAt, Long createdBy, LocalDateTime createdAt,
      Long updatedBy, LocalDateTime updatedAt) {
    this.id = id;
    this.secretChatId = secretChatId;
    this.msgId = msgId;
    this.fromUserId = fromUserId;
    this.ciphertext = ciphertext;
    this.seq = seq;
    this.status = status;
    this.destroyAt = destroyAt;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedBy = updatedBy;
    this.updatedAt = updatedAt;
  }

  public static SecretMessage post(Long secretChatId, String msgId, long fromUserId, String ciphertext, long seq,
      long operatorId, LocalDateTime occurredAt) {
    return new SecretMessage(null, secretChatId, msgId, fromUserId, ciphertext, seq, "active", null,
        operatorId, occurredAt, operatorId, occurredAt);
  }

  public static SecretMessage restore(Long id, Long secretChatId, String msgId, Long fromUserId, String ciphertext,
      Long seq, String status, LocalDateTime destroyAt, Long createdBy, LocalDateTime createdAt,
      Long updatedBy, LocalDateTime updatedAt) {
    return new SecretMessage(id, secretChatId, msgId, fromUserId, ciphertext, seq, status, destroyAt,
        createdBy, createdAt, updatedBy, updatedAt);
  }

  /** 接收方已读后计时：设置定时销毁时间；destroyAt 已存在则保持最早值（不可推迟）。 */
  public SecretMessage scheduleDestroy(LocalDateTime destroyAt, long operatorId, LocalDateTime occurredAt) {
    LocalDateTime effective = this.destroyAt == null || destroyAt.isBefore(this.destroyAt)
        ? destroyAt
        : this.destroyAt;
    return new SecretMessage(id, secretChatId, msgId, fromUserId, ciphertext, seq, status, effective,
        createdBy, createdAt, operatorId, occurredAt);
  }

  public boolean isActive() {
    return "active".equals(status);
  }

  /**
   * 按 TTL 推导销毁时刻：{@code createdAt + ttl}。
   *
   * <p><b>为什么这条规则在领域模型上</b>（standards/10 应用服务规则：核心业务判断与不变量必须下沉到聚合根）：
   * "销毁时刻 = 投递时刻 + TTL"是私密消息的核心不变量。改造前它写在应用服务里
   * （{@code message.getCreatedAt().plus(ttl)}），于是应用层必须先从领域对象读"创建时刻"才能算——
   * 把领域规则摊到了应用层。收到这里之后，应用层只说"按 TTL 安排销毁"，不再知道公式。
   *
   * <p><b>为什么 {@code createdAt} 仍留在本模型</b>：它在本类里不是记账信息，而是上面那条不变量的输入
   * （同时也是响应要回显的投递时刻）。standards/10 "审计列不进领域模型"的立意是防止领域层理解**持久化**语义；
   * 一个被领域规则读取的时刻属于**领域事实**，留在模型里才是它的正确位置。
   * 这与 {@code createdBy}/{@code updatedBy}/{@code updatedAt} 不同——那三者只有记账用途，应下沉适配器。
   */
  public LocalDateTime destroyAtAfter(java.time.Duration ttl) {
    return createdAt == null ? null : createdAt.plus(ttl);
  }

  public Long getId() { return id; }
  public Long getSecretChatId() { return secretChatId; }
  public String getMsgId() { return msgId; }
  public Long getFromUserId() { return fromUserId; }
  public String getCiphertext() { return ciphertext; }
  public Long getSeq() { return seq; }
  public String getStatus() { return status; }
  public LocalDateTime getDestroyAt() { return destroyAt; }
  public Long getCreatedBy() { return createdBy; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public Long getUpdatedBy() { return updatedBy; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
}
