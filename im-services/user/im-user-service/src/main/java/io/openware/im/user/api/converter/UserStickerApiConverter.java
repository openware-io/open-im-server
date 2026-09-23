package io.openware.im.user.api.converter;

import io.openware.im.user.api.dto.response.UserStickerResponse;
import io.openware.im.user.application.sticker.result.UserStickerResult;

public final class UserStickerApiConverter {
  private UserStickerApiConverter() {
  }

  public static UserStickerResponse toResponse(UserStickerResult result) {
    return new UserStickerResponse(
        result.id(), result.userId(), result.url(), result.thumbnail(), result.sortOrder(), result.createdBy(),
        result.createdAt(), result.updatedBy(), result.updatedAt());
  }
}
