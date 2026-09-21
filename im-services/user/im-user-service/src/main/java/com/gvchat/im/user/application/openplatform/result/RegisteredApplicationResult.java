package com.gvchat.im.user.application.openplatform.result;

public record RegisteredApplicationResult(
    String appId,
    String appSecret,
    String appName,
    String status) {
}
