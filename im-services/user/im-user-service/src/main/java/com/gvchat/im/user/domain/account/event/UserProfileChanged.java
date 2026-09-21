package com.gvchat.im.user.domain.account.event;

import java.time.Instant;

public record UserProfileChanged(
    String eventId,
    Long userId,
    String nickname,
    String avatar,
    String phone,
    Instant occurredAt) {
}
