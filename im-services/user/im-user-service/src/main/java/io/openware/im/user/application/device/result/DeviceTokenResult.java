package io.openware.im.user.application.device.result;

import io.openware.common.enums.ClientPlatform;
import io.openware.common.enums.PushProvider;
import java.time.LocalDateTime;

public record DeviceTokenResult(Long id, Long userId, String token, PushProvider pushProvider,
    ClientPlatform platform, String deviceId, Boolean enabled, Long createdBy, LocalDateTime createdAt,
    Long updatedBy, LocalDateTime updatedAt) {
}
