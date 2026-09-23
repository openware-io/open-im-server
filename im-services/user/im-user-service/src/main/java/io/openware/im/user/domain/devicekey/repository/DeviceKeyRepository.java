package io.openware.im.user.domain.devicekey.repository;

import io.openware.im.user.domain.devicekey.model.DeviceKey;
import java.util.List;
import java.util.Optional;

public interface DeviceKeyRepository {
  Optional<DeviceKey> findByUserIdAndDeviceId(long userId, String deviceId);

  List<DeviceKey> findEnabledByUserId(long userId);

  DeviceKey save(DeviceKey deviceKey);
}
