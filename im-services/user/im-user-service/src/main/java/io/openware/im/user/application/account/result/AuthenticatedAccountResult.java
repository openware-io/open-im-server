package io.openware.im.user.application.account.result;

public record AuthenticatedAccountResult(
    String accessToken, Long id, String username, String nickname, String avatar) {
}
