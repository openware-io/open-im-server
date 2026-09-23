package io.openware.im.user.application.device.command;

import io.openware.common.enums.ClientPlatform;
import io.openware.common.enums.PushProvider;

public record RegisterDeviceTokenCommand(String token, ClientPlatform platform, PushProvider pushProvider,
    String deviceId) {
}
