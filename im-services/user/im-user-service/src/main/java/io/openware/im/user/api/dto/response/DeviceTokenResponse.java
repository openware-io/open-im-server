package io.openware.im.user.api.dto.response;

import io.openware.common.enums.ClientPlatform;
import io.openware.common.enums.PushProvider;
import java.time.LocalDateTime;

public record DeviceTokenResponse(Long id, Long userId, String token, PushProvider pushProvider,
    ClientPlatform platform, String deviceId, Boolean enabled, Long createdBy, LocalDateTime createdAt,
    Long updatedBy, LocalDateTime updatedAt) {
}
