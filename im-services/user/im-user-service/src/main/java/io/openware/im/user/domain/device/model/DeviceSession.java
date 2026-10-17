package io.openware.im.user.domain.device.model;

import java.time.LocalDateTime;

/**
 * 设备登录会话实体：记录一台设备的登录 IP、登录方式、设备类型/名称与最后活跃时间。
 *
 * <p>同一 (user_id, device_id) 复用同一条会话（刷新最后活跃时间与登录信息），
 * 不重复建会话，从而支持「一号多设备」在线与设备管理。</p>
 *
 * <p><b>为什么不带审计列</b>（standards/10 L299）：{@code createdBy}/{@code createdAt}/
 * {@code updatedBy}/{@code updatedAt} 是记账信息，由适配器在写库时确定；响应需要的值经
 * {@code DeviceSessionRepository.Persisted} 的 {@code AuditSnapshot} 单独带出。
 *
 * <p><b>与业务时间字段的区别</b>：{@code lastActiveAt} 是设备最后活跃时刻，属业务事实，
 * 因此 {@code start}/{@code refreshActivity} 的 {@code occurredAt} 参数**保留**（它同时写
 * {@code lastActiveAt}）；而 {@code kick}/{@code logout} 的 {@code occurredAt} 原本只写
 * {@code updatedAt}，已随审计列一并移除。
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
  }

  /** 被踢下线。 */
  public void kick() {
    this.status = DeviceSessionStatus.KICKED;
  }

  /** 主动退出登录。 */
  public void logout() {
    this.status = DeviceSessionStatus.LOGOUT;
  }

  public boolean isActive() {
    return status == DeviceSessionStatus.ACTIVE;
  }

  /** 由持久化对象重建领域对象（仅适配器使用）。 */
  public void restore(Long id, Long userId, String deviceId, String deviceType, String deviceName, String loginIp,
      LoginMethod loginMethod, LocalDateTime lastActiveAt, String lastActiveIp, DeviceSessionStatus status) {
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

  public void assignId(Long id) { this.id = id; }
}
