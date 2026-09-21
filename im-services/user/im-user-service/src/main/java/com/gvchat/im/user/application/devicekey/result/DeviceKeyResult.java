package com.gvchat.im.user.application.devicekey.result;

import java.time.LocalDateTime;

public record DeviceKeyResult(Long id, Long userId, String deviceId, String publicKey, String status,
    Long createdBy, LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
}
