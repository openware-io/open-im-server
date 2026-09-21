package com.gvchat.im.user.domain.device.model;

import com.gvchat.common.enums.ClientPlatform;
import com.gvchat.common.enums.PushProvider;
import java.time.LocalDateTime;

public class DeviceToken {
  private Long id;
  private Long userId;
  private String token;
  private PushProvider pushProvider;
  private ClientPlatform platform;
  private String deviceId;
  private Boolean enabled;
  private Long createdBy;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private LocalDateTime updatedAt;

  public static DeviceToken register(Long userId, String token, PushProvider pushProvider,
      ClientPlatform platform, String deviceId, LocalDateTime occurredAt) {
    DeviceToken deviceToken = new DeviceToken();
    deviceToken.userId = userId;
    deviceToken.token = token;
    deviceToken.pushProvider = pushProvider;
    deviceToken.platform = platform;
    deviceToken.deviceId = deviceId;
    deviceToken.enabled = true;
    deviceToken.createdAt = occurredAt;
    deviceToken.updatedAt = occurredAt;
    return deviceToken;
  }

  public void refresh(ClientPlatform platform, String deviceId, LocalDateTime occurredAt) {
    this.enabled = true;
    this.platform = platform;
    this.deviceId = deviceId;
    this.updatedAt = occurredAt;
  }

  public void disable(LocalDateTime occurredAt) {
    this.enabled = false;
    this.updatedAt = occurredAt;
  }

  public void restore(Long id, Long userId, String token, PushProvider pushProvider,
      ClientPlatform platform, String deviceId, Boolean enabled, Long createdBy, LocalDateTime createdAt,
      Long updatedBy, LocalDateTime updatedAt) {
    this.id = id;
    this.userId = userId;
    this.token = token;
    this.pushProvider = pushProvider;
    this.platform = platform;
    this.deviceId = deviceId;
    this.enabled = enabled;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedBy = updatedBy;
    this.updatedAt = updatedAt;
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public String getToken() { return token; }
  public PushProvider getPushProvider() { return pushProvider; }
  public ClientPlatform getPlatform() { return platform; }
  public String getDeviceId() { return deviceId; }
  public Boolean getEnabled() { return enabled; }
  public Long getCreatedBy() { return createdBy; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public Long getUpdatedBy() { return updatedBy; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void assignId(Long id) { this.id = id; }
}
