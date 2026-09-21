package com.gvchat.im.user.api.converter;

import com.gvchat.im.user.api.dto.response.DeviceSessionResponse;
import com.gvchat.im.user.application.device.result.DeviceSessionResult;

public final class DeviceSessionApiConverter {
  private DeviceSessionApiConverter() {
  }

  public static DeviceSessionResponse toResponse(DeviceSessionResult result) {
    return new DeviceSessionResponse(result.id(), result.deviceId(), result.deviceType(), result.deviceName(),
        result.loginIp(), result.loginMethod().value(), result.lastActiveAt(), result.lastActiveIp(),
        result.status().value(), result.createdAt(), result.updatedAt());
  }
}
