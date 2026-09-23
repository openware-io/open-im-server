package io.openware.im.message.api.converter;

import io.openware.im.message.api.dto.response.FavoriteBatchItemResponse;
import io.openware.im.message.api.dto.response.FavoriteBatchResponse;
import io.openware.im.message.api.dto.response.FavoritePageResponse;
import io.openware.im.message.api.dto.response.FavoriteResponse;
import io.openware.im.message.api.dto.response.FavoriteSourceResponse;
import io.openware.im.message.application.favorite.result.FavoriteBatchResult;
import io.openware.im.message.application.favorite.result.FavoritePageResult;
import io.openware.im.message.application.favorite.result.FavoriteResult;
import io.openware.im.message.application.favorite.result.FavoriteSourceResult;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public final class FavoriteApiConverter {
  private FavoriteApiConverter() { }

  public static FavoriteResponse toResponse(FavoriteResult result) {
    return new FavoriteResponse(result.msgId(), result.peerId(), result.chatType(), result.msgType(), result.content(),
        result.senderId(), result.senderUsername(), utc(result.createdAt()), utc(result.favoritedAt()));
  }

  public static FavoritePageResponse toResponse(FavoritePageResult result) {
    return new FavoritePageResponse(result.items().stream().map(FavoriteApiConverter::toResponse).toList(),
        result.total(), result.page(), result.pageSize());
  }

  public static FavoriteBatchResponse toResponse(FavoriteBatchResult result) {
    return new FavoriteBatchResponse(result.created(), result.skipped(),
        result.items().stream().map(FavoriteApiConverter::toResponse).toList());
  }

  private static FavoriteBatchItemResponse toResponse(FavoriteBatchResult.Item item) {
    return new FavoriteBatchItemResponse(item.messageId(), item.created());
  }

  public static FavoriteSourceResponse toResponse(FavoriteSourceResult result) {
    return new FavoriteSourceResponse(result.state(), result.conversationId(), result.messageId());
  }

  private static Instant utc(LocalDateTime value) {
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }
}
