package io.openware.im.message.application.favorite.result;

/**
 * 收藏原消息定位结果：仅 {@link FavoriteSourceState#AVAILABLE} 携带 conversationId/messageId，
 * 其余状态一律不带可跳转信息（fail closed）。
 */
public record FavoriteSourceResult(FavoriteSourceState state, String conversationId, String messageId) {

  public static FavoriteSourceResult available(String conversationId, String messageId) {
    return new FavoriteSourceResult(FavoriteSourceState.AVAILABLE, conversationId, messageId);
  }

  /** 不可跳转状态：不携带任何会话/消息 id，避免泄露或误跳。 */
  public static FavoriteSourceResult of(FavoriteSourceState state) {
    return new FavoriteSourceResult(state, null, null);
  }
}
