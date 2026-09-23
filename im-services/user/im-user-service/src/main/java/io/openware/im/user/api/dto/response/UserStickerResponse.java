package io.openware.im.user.api.dto.response;

import java.time.LocalDateTime;

public record UserStickerResponse(
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
