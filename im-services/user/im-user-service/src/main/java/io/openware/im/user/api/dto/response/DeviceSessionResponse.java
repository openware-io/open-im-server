package io.openware.im.user.api.dto.response;

import java.time.LocalDateTime;

/** 设备登录会话响应。 */
public record DeviceSessionResponse(Long id, String deviceId, String deviceType, String deviceName, String loginIp,
    String loginMethod, LocalDateTime lastActiveAt, String lastActiveIp, String status, LocalDateTime createdAt,
    LocalDateTime updatedAt) {
}
