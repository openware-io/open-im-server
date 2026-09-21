package com.gvchat.im.user.api.dto.response;

import com.gvchat.common.enums.ClientPlatform;
import com.gvchat.common.enums.PushProvider;
import java.time.LocalDateTime;

public record DeviceTokenResponse(Long id, Long userId, String token, PushProvider pushProvider,
    ClientPlatform platform, String deviceId, Boolean enabled, Long createdBy, LocalDateTime createdAt,
    Long updatedBy, LocalDateTime updatedAt) {
}
