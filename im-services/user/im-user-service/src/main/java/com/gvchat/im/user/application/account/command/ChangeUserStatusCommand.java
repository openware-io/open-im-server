package com.gvchat.im.user.application.account.command;

import com.gvchat.im.user.domain.account.model.UserAccountStatus;

public record ChangeUserStatusCommand(
    int requestVersion,
    Long userId,
    UserAccountStatus status,
    long expectedStatusVersion,
    String idempotencyKey,
    Long operatorId,
    String reason,
    String correlationId) {
}
