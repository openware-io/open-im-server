package io.openware.im.user.application.devicekey;

import io.openware.im.user.api.authorization.UserDeviceKeySummary;
import io.openware.im.user.application.devicekey.command.RegisterDeviceKeyCommand;
import io.openware.im.user.application.devicekey.result.DeviceKeyResult;
import io.openware.im.user.domain.devicekey.model.DeviceKey;
import io.openware.im.user.domain.devicekey.repository.DeviceKeyRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeviceKeyApplicationService {
  private final DeviceKeyRepository deviceKeyRepository;

  @Transactional
  public DeviceKeyResult register(Long userId, RegisterDeviceKeyCommand command) {
    LocalDateTime occurredAt = LocalDateTime.now();
    DeviceKey deviceKey = deviceKeyRepository
        .findByUserIdAndDeviceId(userId, command.deviceId())
        .map(existing -> existing.renew(command.publicKey(), userId, occurredAt))
        .orElseGet(() -> DeviceKey.register(userId, command.deviceId(), command.publicKey(), userId, occurredAt));
    return toResult(deviceKeyRepository.save(deviceKey));
  }

  @Transactional(readOnly = true)
  public List<DeviceKeyResult> getMyDeviceKeys(Long userId) {
    return deviceKeyRepository.findEnabledByUserId(userId).stream().map(this::toResult).toList();
  }

  /** 批量查询用户设备公钥（每个用户取最新一条 active 设备密钥，供私密聊天预填对端公钥）。 */
  @Transactional(readOnly = true)
  public List<UserDeviceKeySummary> findDeviceKeySummaries(List<Long> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return List.of();
    }
    return userIds.stream().distinct()
        .map(deviceKeyRepository::findEnabledByUserId)
        .filter(keys -> !keys.isEmpty())
        .map(keys -> keys.stream().max(Comparator.comparing(DeviceKey::getId)).orElseThrow())
        .map(key -> new UserDeviceKeySummary(key.getUserId(), key.getPublicKey()))
        .toList();
  }

  private DeviceKeyResult toResult(DeviceKey key) {
    return new DeviceKeyResult(key.getId(), key.getUserId(), key.getDeviceId(), key.getPublicKey(),
        key.getStatus(), key.getCreatedBy(), key.getCreatedAt(), key.getUpdatedBy(), key.getUpdatedAt());
  }
}
