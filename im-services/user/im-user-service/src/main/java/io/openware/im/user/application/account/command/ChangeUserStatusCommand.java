package io.openware.im.user.application.account.command;

import io.openware.im.user.domain.account.model.UserAccountStatus;

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
