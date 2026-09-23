package io.openware.im.user.api.converter;

import io.openware.im.user.api.dto.response.DeviceSessionResponse;
import io.openware.im.user.application.device.result.DeviceSessionResult;

public final class DeviceSessionApiConverter {
  private DeviceSessionApiConverter() {
  }

  public static DeviceSessionResponse toResponse(DeviceSessionResult result) {
    return new DeviceSessionResponse(result.id(), result.deviceId(), result.deviceType(), result.deviceName(),
        result.loginIp(), result.loginMethod().value(), result.lastActiveAt(), result.lastActiveIp(),
        result.status().value(), result.createdAt(), result.updatedAt());
  }
}
