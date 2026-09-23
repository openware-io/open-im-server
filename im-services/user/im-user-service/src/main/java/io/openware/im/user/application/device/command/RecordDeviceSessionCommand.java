package io.openware.im.user.application.device.command;

import io.openware.im.user.domain.device.model.LoginMethod;

/** 记录（或刷新）一次设备登录会话的命令。 */
public record RecordDeviceSessionCommand(Long userId, String deviceId, String deviceType, String deviceName,
    LoginMethod loginMethod, String ip) {
}
