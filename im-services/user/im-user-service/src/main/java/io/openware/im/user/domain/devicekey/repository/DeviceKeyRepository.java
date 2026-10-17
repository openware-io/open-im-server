package io.openware.im.user.domain.devicekey.repository;

import io.openware.im.user.domain.devicekey.model.DeviceKey;
import java.util.List;
import java.util.Optional;

public interface DeviceKeyRepository {
  Optional<Persisted<DeviceKey>> findByUserIdAndDeviceId(long userId, String deviceId);

  List<Persisted<DeviceKey>> findEnabledByUserId(long userId);

  Persisted<DeviceKey> save(DeviceKey deviceKey);
}
