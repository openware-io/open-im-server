package com.gvchat.im.user.application.device.command;

import com.gvchat.common.enums.PushProvider;

public record RemoveDeviceTokenCommand(String token, PushProvider pushProvider) {
}
