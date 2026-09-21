package com.gvchat.im.user.api.dto.response;

import java.time.LocalDateTime;

public record UserProfileResponse(
    Long id,
    String username,
    String nickname,
    String avatar,
    String email,
    String phone,
    String signature,
    String status,
    String role,
    Long createdBy,
    LocalDateTime createdAt,
    Long updatedBy,
    LocalDateTime updatedAt) {
}
