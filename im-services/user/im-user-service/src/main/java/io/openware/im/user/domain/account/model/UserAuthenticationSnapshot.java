package io.openware.im.user.domain.account.model;

public record UserAuthenticationSnapshot(
    long userId,
    String username,
    UserAccountRole role,
    boolean active,
    long authenticationVersion) {
}
