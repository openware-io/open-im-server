package io.openware.im.user.domain.devicekey.model;

/**
 * 用户设备公钥聚合（E2EE 设备身份）。私钥永不离开客户端，服务端只存公钥。
 *
 * <p><b>为什么不带审计列</b>（standards/10 L299）：{@code createdBy}/{@code createdAt}/
 * {@code updatedBy}/{@code updatedAt} 是记账信息，由适配器在写库时确定；响应需要的值经
 * {@code DeviceKeyRepository.Persisted} 的 {@code AuditSnapshot} 单独带出。
 * 操作人恒为密钥所属用户，适配器可由 {@code userId} 得出。
 */
public class DeviceKey {
  private final Long id;
  private final Long userId;
  private final String deviceId;
  private final String publicKey;
  private final String status;

  private DeviceKey(Long id, Long userId, String deviceId, String publicKey, String status) {
    this.id = id;
    this.userId = userId;
    this.deviceId = deviceId;
    this.publicKey = publicKey;
    this.status = status;
  }

  public static DeviceKey register(long userId, String deviceId, String publicKey) {
    return new DeviceKey(null, userId, deviceId, publicKey, "active");
  }

  public DeviceKey renew(String newPublicKey) {
    return new DeviceKey(id, userId, deviceId, newPublicKey, status);
  }

  /** 由持久化对象重建领域对象（仅适配器使用）。 */
  public static DeviceKey restore(Long id, Long userId, String deviceId, String publicKey, String status) {
    return new DeviceKey(id, userId, deviceId, publicKey, status);
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public String getDeviceId() { return deviceId; }
  public String getPublicKey() { return publicKey; }
  public String getStatus() { return status; }
}
