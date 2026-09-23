package io.openware.im.user.api.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.openware.common.enums.ClientPlatform;
import io.openware.common.enums.PushProvider;
import io.openware.im.user.api.dto.response.DeviceTokenResponse;
import io.openware.im.user.application.device.result.DeviceTokenResult;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class DeviceTokenApiConverterTest {
  @Test
  void shouldPreserveDeviceTokenResponseFields() {
    LocalDateTime occurredAt = LocalDateTime.of(2026, 7, 22, 12, 0);
    DeviceTokenResponse response = DeviceTokenApiConverter.toResponse(new DeviceTokenResult(1L, 2L, "token",
        PushProvider.FCM, ClientPlatform.ANDROID, "device", true, 3L, occurredAt, 4L, occurredAt));

    assertEquals(1L, response.id());
    assertEquals(2L, response.userId());
    assertEquals("token", response.token());
    assertEquals(PushProvider.FCM, response.pushProvider());
    assertEquals(ClientPlatform.ANDROID, response.platform());
    assertEquals("device", response.deviceId());
    assertEquals(true, response.enabled());
    assertEquals(3L, response.createdBy());
    assertEquals(occurredAt, response.createdAt());
    assertEquals(4L, response.updatedBy());
    assertEquals(occurredAt, response.updatedAt());
  }
}
