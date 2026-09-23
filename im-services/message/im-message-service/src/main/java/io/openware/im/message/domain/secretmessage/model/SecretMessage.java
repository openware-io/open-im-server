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
