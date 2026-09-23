package io.openware.im.message.domain.secretgroupmessage.model;

import java.time.LocalDateTime;

/** 私密群聊消息聚合根：逐成员一份密文，服务端不解密；定时销毁由 destroyAt 驱动。 */
public class SecretGroupMessage {
  private final Long id;
  private final Long secretGroupId;
  private final String msgId;
  private final Long fromUserId;
  private final Long recipientUserId;
  private final String ciphertext;
  private final Long seq;
  private final String status;
  private final LocalDateTime destroyAt;
  private final LocalDateTime createdAt;

  private SecretGroupMessage(Long id, Long secretGroupId, String msgId, Long fromUserId, Long recipientUserId,
      String ciphertext, Long seq, String status, LocalDateTime destroyAt, LocalDateTime createdAt) {
    this.id = id;
    this.secretGroupId = secretGroupId;
    this.msgId = msgId;
    this.fromUserId = fromUserId;
    this.recipientUserId = recipientUserId;
    this.ciphertext = ciphertext;
    this.seq = seq;
    this.status = status;
    this.destroyAt = destroyAt;
    this.createdAt = createdAt;
  }

  public static SecretGroupMessage post(long secretGroupId, String msgId, long fromUserId, long recipientUserId,
      String ciphertext, long seq, LocalDateTime occurredAt) {
    return new SecretGroupMessage(null, secretGroupId, msgId, fromUserId, recipientUserId, ciphertext, seq, "active",
        null, occurredAt);
  }

  public SecretGroupMessage scheduleDestroy(LocalDateTime destroyAt) {
    LocalDateTime effective = this.destroyAt == null || destroyAt.isBefore(this.destroyAt)
        ? destroyAt
        : this.destroyAt;
    return new SecretGroupMessage(id, secretGroupId, msgId, fromUserId, recipientUserId, ciphertext, seq, status,
        effective, createdAt);
  }

  public boolean isActive() {
    return "active".equals(status);
  }

  public static SecretGroupMessage restore(Long id, Long secretGroupId, String msgId, Long fromUserId,
      Long recipientUserId, String ciphertext, Long seq, String status, LocalDateTime destroyAt, LocalDateTime createdAt) {
    return new SecretGroupMessage(id, secretGroupId, msgId, fromUserId, recipientUserId, ciphertext, seq, status,
        destroyAt, createdAt);
  }

  public Long getId() { return id; }
  public Long getSecretGroupId() { return secretGroupId; }
  public String getMsgId() { return msgId; }
  public Long getFromUserId() { return fromUserId; }
  public Long getRecipientUserId() { return recipientUserId; }
  public String getCiphertext() { return ciphertext; }
  public Long getSeq() { return seq; }
  public String getStatus() { return status; }
  public LocalDateTime getDestroyAt() { return destroyAt; }
  public LocalDateTime getCreatedAt() { return createdAt; }
}
