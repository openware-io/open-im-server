package com.gvchat.im.user.api.authorization;

/** 用户设备公钥摘要（仅公钥，绝不含私钥）。 */
public record UserDeviceKeySummary(long userId, String publicKey) {
}
