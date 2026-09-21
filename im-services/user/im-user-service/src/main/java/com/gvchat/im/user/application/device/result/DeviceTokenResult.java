package com.gvchat.im.user.application.device.result;

import com.gvchat.common.enums.ClientPlatform;
import com.gvchat.common.enums.PushProvider;
import java.time.LocalDateTime;

public record DeviceTokenResult(Long id, Long userId, String token, PushProvider pushProvider,
    ClientPlatform platform, String deviceId, Boolean enabled, Long createdBy, LocalDateTime createdAt,
    Long updatedBy, LocalDateTime updatedAt) {
}
