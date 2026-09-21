package com.gvchat.im.user.api.admin;

import com.gvchat.common.enums.UserStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChangeUserStatusRequest(
    @Min(1) int requestVersion,
    @NotNull UserStatus status,
    @Min(1) long expectedStatusVersion,
    @NotBlank @Size(max = 128) String idempotencyKey,
    @NotNull Long operatorId,
    @NotBlank @Size(max = 512) String reason,
    @NotBlank @Size(max = 128) String correlationId) {
}
