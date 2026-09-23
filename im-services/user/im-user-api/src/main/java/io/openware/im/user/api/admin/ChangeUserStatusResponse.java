package io.openware.im.user.api.admin;

import io.openware.common.enums.UserStatus;
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
