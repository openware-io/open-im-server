package io.openware.im.user.application.sticker.result;

import java.time.LocalDateTime;

public record UserStickerResult(
    Long id,
    Long userId,
    String url,
    String thumbnail,
    Integer sortOrder,
    Long createdBy,
    LocalDateTime createdAt,
    Long updatedBy,
    LocalDateTime updatedAt) {
}
