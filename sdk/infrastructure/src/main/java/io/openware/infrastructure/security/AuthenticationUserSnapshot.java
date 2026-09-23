package io.openware.infrastructure.security;

import io.openware.common.enums.UserRole;

public record AuthenticationUserSnapshot(
    long userId,
    String username,
    UserRole role,
    boolean active,
    long authenticationVersion) {
}
