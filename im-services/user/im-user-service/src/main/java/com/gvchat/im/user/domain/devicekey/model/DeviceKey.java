package com.gvchat.im.user.domain.devicekey.model;

import java.time.LocalDateTime;

/** 用户设备公钥聚合（E2EE 设备身份）。私钥永不离开客户端，服务端只存公钥。 */
public class DeviceKey {
  private final Long id;
  private final Long userId;
  private final String deviceId;
  private final String publicKey;
  private final String status;
  private final Long createdBy;
  private final LocalDateTime createdAt;
  private final Long updatedBy;
  private final LocalDateTime updatedAt;

  private DeviceKey(Long id, Long userId, String deviceId, String publicKey, String status,
      Long createdBy, LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
    this.id = id;
    this.userId = userId;
    this.deviceId = deviceId;
    this.publicKey = publicKey;
    this.status = status;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedBy = updatedBy;
    this.updatedAt = updatedAt;
  }

  public static DeviceKey register(long userId, String deviceId, String publicKey,
      long operatorId, LocalDateTime occurredAt) {
    return new DeviceKey(null, userId, deviceId, publicKey, "active", operatorId, occurredAt, operatorId, occurredAt);
  }

  public DeviceKey renew(String newPublicKey, long operatorId, LocalDateTime occurredAt) {
    return new DeviceKey(id, userId, deviceId, newPublicKey, status, createdBy, createdAt, operatorId, occurredAt);
  }

  public static DeviceKey restore(Long id, Long userId, String deviceId, String publicKey, String status,
      Long createdBy, LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
    return new DeviceKey(id, userId, deviceId, publicKey, status, createdBy, createdAt, updatedBy, updatedAt);
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public String getDeviceId() { return deviceId; }
  public String getPublicKey() { return publicKey; }
  public String getStatus() { return status; }
  public Long getCreatedBy() { return createdBy; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public Long getUpdatedBy() { return updatedBy; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
}
