package io.openware.im.user.application.devicekey.command;

public record RegisterDeviceKeyCommand(String deviceId, String publicKey) {
}
