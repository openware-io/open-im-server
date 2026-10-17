package io.openware.im.user.domain.device.model;

import io.openware.common.enums.ClientPlatform;
import io.openware.common.enums.PushProvider;

/**
 * 推送令牌：一台设备在一个推送渠道上的注册令牌。
 *
 * <p><b>为什么不带审计列</b>（standards/10 L299）：{@code createdBy}/{@code createdAt}/
 * {@code updatedBy}/{@code updatedAt} 是记账信息，由适配器在写库时确定。
 * 详情响应需要的值经 {@code DeviceTokenRepository.Persisted} 带入；管理端列表是只读聚合查询，
 * 按 L266-279 走 {@code DeviceTokenView} 投影。操作人恒为令牌所属用户，适配器由 {@code userId} 得出。
 */
public class DeviceToken {
  private Long id;
  private Long userId;
  private String token;
  private PushProvider pushProvider;
  private ClientPlatform platform;
  private String deviceId;
  private Boolean enabled;

  public static DeviceToken register(Long userId, String token, PushProvider pushProvider,
      ClientPlatform platform, String deviceId) {
    DeviceToken deviceToken = new DeviceToken();
    deviceToken.userId = userId;
    deviceToken.token = token;
    deviceToken.pushProvider = pushProvider;
    deviceToken.platform = platform;
    deviceToken.deviceId = deviceId;
    deviceToken.enabled = true;
    return deviceToken;
  }

  public void refresh(ClientPlatform platform, String deviceId) {
    this.enabled = true;
    this.platform = platform;
    this.deviceId = deviceId;
  }

  public void disable() {
    this.enabled = false;
  }

  /** 由持久化对象重建领域对象（仅适配器使用）。 */
  public void restore(Long id, Long userId, String token, PushProvider pushProvider,
      ClientPlatform platform, String deviceId, Boolean enabled) {
    this.id = id;
    this.userId = userId;
    this.token = token;
    this.pushProvider = pushProvider;
    this.platform = platform;
    this.deviceId = deviceId;
    this.enabled = enabled;
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public String getToken() { return token; }
  public PushProvider getPushProvider() { return pushProvider; }
  public ClientPlatform getPlatform() { return platform; }
  public String getDeviceId() { return deviceId; }
  public Boolean getEnabled() { return enabled; }
  public void assignId(Long id) { this.id = id; }
}
