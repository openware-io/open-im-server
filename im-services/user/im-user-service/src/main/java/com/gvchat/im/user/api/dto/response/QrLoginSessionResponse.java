package com.gvchat.im.user.api.dto.response;

import java.time.Instant;

public record QrLoginSessionResponse(String qrToken, Instant expiresAt) {
}
