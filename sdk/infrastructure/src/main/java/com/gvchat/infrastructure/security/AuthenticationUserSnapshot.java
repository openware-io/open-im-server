package com.gvchat.infrastructure.security;

import com.gvchat.common.enums.UserRole;

public record AuthenticationUserSnapshot(
    long userId,
    String username,
    UserRole role,
    boolean active,
    long authenticationVersion) {
}
