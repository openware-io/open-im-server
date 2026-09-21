package com.gvchat.im.user.api.admin;

import com.gvchat.common.enums.UserStatus;
import java.time.LocalDateTime;

public record ChangeUserStatusResponse(
    int responseVersion,
    Long userId,
    UserStatus previousStatus,
    UserStatus status,
    long statusVersion,
    String idempotencyKey,
    String correlationId,
    LocalDateTime occurredAt) {
}
