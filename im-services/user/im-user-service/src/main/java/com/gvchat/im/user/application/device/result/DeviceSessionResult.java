package com.gvchat.im.user.application.device.result;

import com.gvchat.im.user.domain.device.model.DeviceSessionStatus;
import com.gvchat.im.user.domain.device.model.LoginMethod;
import java.time.LocalDateTime;

/** 设备登录会话查询结果。 */
public record DeviceSessionResult(Long id, Long userId, String deviceId, String deviceType, String deviceName,
    String loginIp, LoginMethod loginMethod, LocalDateTime lastActiveAt, String lastActiveIp,
    DeviceSessionStatus status, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
