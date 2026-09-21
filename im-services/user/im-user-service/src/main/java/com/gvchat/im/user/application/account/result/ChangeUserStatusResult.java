package com.gvchat.im.user.application.account.result;

import com.gvchat.im.user.domain.account.model.UserAccountStatus;
import java.time.LocalDateTime;

public record ChangeUserStatusResult(
    int responseVersion,
    Long userId,
    UserAccountStatus previousStatus,
    UserAccountStatus status,
    long statusVersion,
    String idempotencyKey,
    String correlationId,
    LocalDateTime occurredAt) {
}
