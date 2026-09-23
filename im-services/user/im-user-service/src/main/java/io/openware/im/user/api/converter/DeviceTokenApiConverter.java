package io.openware.im.user.api.converter;

import io.openware.im.user.api.dto.response.DeviceTokenResponse;
import io.openware.im.user.application.device.result.DeviceTokenResult;

public final class DeviceTokenApiConverter {
  private DeviceTokenApiConverter() {
  }

  public static DeviceTokenResponse toResponse(DeviceTokenResult result) {
    return new DeviceTokenResponse(result.id(), result.userId(), result.token(), result.pushProvider(),
        result.platform(), result.deviceId(), result.enabled(), result.createdBy(), result.createdAt(),
        result.updatedBy(), result.updatedAt());
  }
}
