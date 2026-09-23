package io.openware.im.user.application.device;

import io.openware.common.enums.PushProvider;
import io.openware.im.user.application.device.command.RegisterDeviceTokenCommand;
import io.openware.im.user.application.device.command.RemoveDeviceTokenCommand;
import io.openware.im.user.application.device.result.DeviceTokenResult;
import io.openware.im.user.domain.device.model.DeviceToken;
import io.openware.im.user.domain.device.repository.DeviceTokenRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeviceTokenApplicationService {
  private final DeviceTokenRepository deviceTokenRepository;

  @Transactional
  public DeviceTokenResult register(Long userId, RegisterDeviceTokenCommand command) {
    PushProvider provider = command.pushProvider() == null ? PushProvider.JPUSH : command.pushProvider();
    LocalDateTime occurredAt = LocalDateTime.now();
    DeviceToken deviceToken = deviceTokenRepository
        .findByUserIdAndPushProviderAndToken(userId, provider, command.token())
        .map(existing -> {
          existing.refresh(command.platform(), command.deviceId(), occurredAt);
          return existing;
        })
        .orElseGet(() -> DeviceToken.register(userId, command.token(), provider, command.platform(),
            command.deviceId(), occurredAt));
    return toResult(deviceTokenRepository.save(deviceToken));
  }

  @Transactional
  public void remove(Long userId, RemoveDeviceTokenCommand command) {
    PushProvider provider = command.pushProvider() == null ? PushProvider.JPUSH : command.pushProvider();
    deviceTokenRepository.findByUserIdAndPushProviderAndToken(userId, provider, command.token())
        .ifPresent(deviceToken -> {
          deviceToken.disable(LocalDateTime.now());
          deviceTokenRepository.save(deviceToken);
        });
  }

  @Transactional(readOnly = true)
  public List<DeviceTokenResult> getTokensForUser(Long userId) {
    return deviceTokenRepository.findEnabledByUserId(userId).stream().map(this::toResult).toList();
  }

  @Transactional(readOnly = true)
  public List<DeviceTokenResult> getTokensForUsers(Collection<Long> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return List.of();
    }
    return deviceTokenRepository.findEnabledByUserIds(userIds).stream().map(this::toResult).toList();
  }

  private DeviceTokenResult toResult(DeviceToken deviceToken) {
    return new DeviceTokenResult(deviceToken.getId(), deviceToken.getUserId(), deviceToken.getToken(),
        deviceToken.getPushProvider(), deviceToken.getPlatform(), deviceToken.getDeviceId(),
        deviceToken.getEnabled(), deviceToken.getCreatedBy(), deviceToken.getCreatedAt(),
        deviceToken.getUpdatedBy(), deviceToken.getUpdatedAt());
  }
}
