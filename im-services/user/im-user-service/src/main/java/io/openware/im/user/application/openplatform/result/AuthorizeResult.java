package io.openware.im.user.application.openplatform.result;

public record AuthorizeResult(
    String redirectUri,
    String authCode,
    String state) {
}
