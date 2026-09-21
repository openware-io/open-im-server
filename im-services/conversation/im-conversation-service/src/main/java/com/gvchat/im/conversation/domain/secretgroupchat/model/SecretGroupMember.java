package com.gvchat.im.conversation.domain.secretgroupchat.model;

import java.time.LocalDateTime;

/** 私密群聊成员：成员设备公钥（握手），服务端仅存储转发。 */
public class SecretGroupMember {
  private final Long id;
  private final Long secretGroupId;
  private final Long userId;
  private final String devicePublicKey;
  private final LocalDateTime joinedAt;

  private SecretGroupMember(Long id, Long secretGroupId, Long userId, String devicePublicKey, LocalDateTime joinedAt) {
    this.id = id;
    this.secretGroupId = secretGroupId;
    this.userId = userId;
    this.devicePublicKey = devicePublicKey;
    this.joinedAt = joinedAt;
  }

  public static SecretGroupMember join(long secretGroupId, long userId, LocalDateTime occurredAt) {
    return new SecretGroupMember(null, secretGroupId, userId, null, occurredAt);
  }

  public SecretGroupMember submitPublicKey(String publicKey) {
    return new SecretGroupMember(id, secretGroupId, userId, publicKey, joinedAt);
  }

  public boolean hasPublicKey() {
    return devicePublicKey != null && !devicePublicKey.isBlank();
  }

  public static SecretGroupMember restore(Long id, Long secretGroupId, Long userId, String devicePublicKey,
      LocalDateTime joinedAt) {
    return new SecretGroupMember(id, secretGroupId, userId, devicePublicKey, joinedAt);
  }

  public Long getId() { return id; }
  public Long getSecretGroupId() { return secretGroupId; }
  public Long getUserId() { return userId; }
  public String getDevicePublicKey() { return devicePublicKey; }
  public LocalDateTime getJoinedAt() { return joinedAt; }
}
