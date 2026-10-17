package io.openware.im.message.domain.secretgroupmessage.model;

import java.time.LocalDateTime;

/**
 * 私密群聊消息聚合根：逐成员一份密文，服务端不解密；定时销毁由 destroyAt 驱动。
 *
 * <p><b>为什么不带 {@code createdAt}</b>（standards/10 §聚合装配与持久化契约）：投递时刻是记账信息，
 * 由适配器在写库时确定；响应需要的值经 {@code SecretGroupMessageRepository.Persisted} 的
 * {@code AuditSnapshot} 单独带出。
 *
 * <p>与本类不同，{@code SecretMessage} 的 {@code createdAt} 留在模型里，因为那条不变式
 * （销毁时刻 = 投递时刻 + TTL）需要读它。本类的 {@code scheduleDestroy} 由调用方显式给出销毁时刻，
 * 没有这条依赖，所以它可以完整下沉到适配器。
 */
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

  private SecretGroupMessage(Long id, Long secretGroupId, String msgId, Long fromUserId, Long recipientUserId,
      String ciphertext, Long seq, String status, LocalDateTime destroyAt) {
    this.id = id;
    this.secretGroupId = secretGroupId;
    this.msgId = msgId;
    this.fromUserId = fromUserId;
    this.recipientUserId = recipientUserId;
    this.ciphertext = ciphertext;
    this.seq = seq;
    this.status = status;
    this.destroyAt = destroyAt;
  }

  public static SecretGroupMessage post(long secretGroupId, String msgId, long fromUserId, long recipientUserId,
      String ciphertext, long seq) {
    return new SecretGroupMessage(null, secretGroupId, msgId, fromUserId, recipientUserId, ciphertext, seq,
        "active", null);
  }

  public SecretGroupMessage scheduleDestroy(LocalDateTime destroyAt) {
    LocalDateTime effective = this.destroyAt == null || destroyAt.isBefore(this.destroyAt)
        ? destroyAt
        : this.destroyAt;
    return new SecretGroupMessage(id, secretGroupId, msgId, fromUserId, recipientUserId, ciphertext, seq, status,
        effective);
  }

  public boolean isActive() {
    return "active".equals(status);
  }

  /** 由持久化对象重建领域对象（仅适配器使用）。 */
  public static SecretGroupMessage restore(Long id, Long secretGroupId, String msgId, Long fromUserId,
      Long recipientUserId, String ciphertext, Long seq, String status, LocalDateTime destroyAt) {
    return new SecretGroupMessage(id, secretGroupId, msgId, fromUserId, recipientUserId, ciphertext, seq, status,
        destroyAt);
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
}
