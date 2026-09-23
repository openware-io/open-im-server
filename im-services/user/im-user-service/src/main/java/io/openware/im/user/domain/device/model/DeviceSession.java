package io.openware.im.user.domain.device.model;

import java.time.LocalDateTime;

/**
 * 设备登录会话实体：记录一台设备的登录 IP、登录方式、设备类型/名称与最后活跃时间。
 *
 * <p>同一 (user_id, device_id) 复用同一条会话（刷新最后活跃时间与登录信息），
 * 不重复建会话，从而支持「一号多设备」在线与设备管理。</p>
 */
public class DeviceSession {
  private Long id;
  private Long userId;
  private String deviceId;
  private String deviceType;
  private String deviceName;
  private String loginIp;
  private LoginMethod loginMethod;
  private LocalDateTime lastActiveAt;
  private String lastActiveIp;
  private DeviceSessionStatus status;
  private Long createdBy;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private LocalDateTime updatedAt;

  /** 新建会话：记录首次登录信息并置为 active。 */
  public static DeviceSession start(Long userId, String deviceId, String deviceType, String deviceName,
      LoginMethod loginMethod, String ip, LocalDateTime occurredAt) {
    DeviceSession session = new DeviceSession();
    session.userId = userId;
    session.deviceId = deviceId;
    session.deviceType = deviceType;
    session.deviceName = deviceName;
    session.loginIp = ip;
    session.loginMethod = loginMethod == null ? LoginMethod.PASSWORD : loginMethod;
    session.lastActiveAt = occurredAt;
    session.lastActiveIp = ip;
    session.status = DeviceSessionStatus.ACTIVE;
    session.createdBy = userId;
    session.createdAt = occurredAt;
    session.updatedBy = userId;
    session.updatedAt = occurredAt;
    return session;
  }

  /** 同设备再次登录：复用会话并刷新登录方式、设备信息与最后活跃时间，并恢复为 active。 */
  public void refreshActivity(String deviceType, String deviceName, LoginMethod loginMethod, String ip,
      LocalDateTime occurredAt) {
    if (deviceType != null) {
      this.deviceType = deviceType;
    }
    if (deviceName != null) {
      this.deviceName = deviceName;
    }
    if (loginMethod != null) {
      this.loginMethod = loginMethod;
    }
    if (ip != null) {
      this.loginIp = ip;
      this.lastActiveIp = ip;
    }
    this.lastActiveAt = occurredAt;
    this.status = DeviceSessionStatus.ACTIVE;
    this.updatedAt = occurredAt;
  }

  /** 被踢下线。 */
  public void kick(LocalDateTime occurredAt) {
    this.status = DeviceSessionStatus.KICKED;
    this.updatedAt = occurredAt;
  }

  /** 主动退出登录。 */
  public void logout(LocalDateTime occurredAt) {
    this.status = DeviceSessionStatus.LOGOUT;
    this.updatedAt = occurredAt;
  }

  public boolean isActive() {
    return status == DeviceSessionStatus.ACTIVE;
  }

  public void restore(Long id, Long userId, String deviceId, String deviceType, String deviceName, String loginIp,
      LoginMethod loginMethod, LocalDateTime lastActiveAt, String lastActiveIp, DeviceSessionStatus status,
      Long createdBy, LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
    this.id = id;
    this.userId = userId;
    this.deviceId = deviceId;
    this.deviceType = deviceType;
    this.deviceName = deviceName;
    this.loginIp = loginIp;
    this.loginMethod = loginMethod;
    this.lastActiveAt = lastActiveAt;
    this.lastActiveIp = lastActiveIp;
    this.status = status;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedBy = updatedBy;
    this.updatedAt = updatedAt;
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public String getDeviceId() { return deviceId; }
  public String getDeviceType() { return deviceType; }
  public String getDeviceName() { return deviceName; }
  public String getLoginIp() { return loginIp; }
  public LoginMethod getLoginMethod() { return loginMethod; }
  public LocalDateTime getLastActiveAt() { return lastActiveAt; }
  public String getLastActiveIp() { return lastActiveIp; }
  public DeviceSessionStatus getStatus() { return status; }
  public Long getCreatedBy() { return createdBy; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public Long getUpdatedBy() { return updatedBy; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }

  public void assignId(Long id) { this.id = id; }
}
