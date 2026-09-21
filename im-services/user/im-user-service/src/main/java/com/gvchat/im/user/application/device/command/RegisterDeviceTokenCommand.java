package com.gvchat.im.user.application.device.command;

import com.gvchat.common.enums.ClientPlatform;
import com.gvchat.common.enums.PushProvider;

public record RegisterDeviceTokenCommand(String token, ClientPlatform platform, PushProvider pushProvider,
    String deviceId) {
}
