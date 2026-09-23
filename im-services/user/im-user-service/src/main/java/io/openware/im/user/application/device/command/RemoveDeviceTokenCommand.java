package io.openware.im.user.application.device.command;

import io.openware.common.enums.PushProvider;

public record RemoveDeviceTokenCommand(String token, PushProvider pushProvider) {
}
