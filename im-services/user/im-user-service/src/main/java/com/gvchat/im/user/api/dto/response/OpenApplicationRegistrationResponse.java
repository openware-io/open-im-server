package com.gvchat.im.user.api.dto.response;

public record OpenApplicationRegistrationResponse(
    String appId,
    String appSecret,
    String appName,
    String status) {
}
