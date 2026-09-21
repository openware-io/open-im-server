package com.gvchat.im.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record QrLoginConfirmRequest(@NotBlank String qrToken) {
}
