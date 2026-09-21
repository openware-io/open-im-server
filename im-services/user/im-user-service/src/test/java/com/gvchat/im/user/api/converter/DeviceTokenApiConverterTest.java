package com.gvchat.im.user.api.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gvchat.common.enums.ClientPlatform;
import com.gvchat.common.enums.PushProvider;
import com.gvchat.im.user.api.dto.response.DeviceTokenResponse;
import com.gvchat.im.user.application.device.result.DeviceTokenResult;
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
