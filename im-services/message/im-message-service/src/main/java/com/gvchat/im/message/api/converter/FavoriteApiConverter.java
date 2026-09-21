package com.gvchat.im.message.api.converter;

import com.gvchat.im.message.api.dto.response.FavoriteBatchItemResponse;
import com.gvchat.im.message.api.dto.response.FavoriteBatchResponse;
import com.gvchat.im.message.api.dto.response.FavoritePageResponse;
import com.gvchat.im.message.api.dto.response.FavoriteResponse;
import com.gvchat.im.message.api.dto.response.FavoriteSourceResponse;
import com.gvchat.im.message.application.favorite.result.FavoriteBatchResult;
import com.gvchat.im.message.application.favorite.result.FavoritePageResult;
import com.gvchat.im.message.application.favorite.result.FavoriteResult;
import com.gvchat.im.message.application.favorite.result.FavoriteSourceResult;
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
