package io.openware.im.user.domain.device.repository;

import io.openware.im.user.domain.device.model.DeviceSession;
import java.util.List;
import java.util.Optional;

/** 设备登录会话仓储契约。 */
public interface DeviceSessionRepository {
  Optional<Persisted<DeviceSession>> findByUserIdAndDeviceId(Long userId, String deviceId);

  Optional<Persisted<DeviceSession>> findById(Long id);

  /** 查询指定用户的全部设备会话，按最后活跃时间倒序。 */
  List<Persisted<DeviceSession>> findByUserId(Long userId);

  Persisted<DeviceSession> save(DeviceSession session);
}
