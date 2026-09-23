package io.openware.im.message.api.dto.response;

import io.openware.im.message.application.favorite.result.FavoriteSourceState;

/**
 * 收藏原消息定位响应：只有 state=AVAILABLE 时 conversationId/messageId 非空，其余状态一律为 null
 * （Jackson 配置 non_null，字段会直接从响应中省略）。
 */
public record FavoriteSourceResponse(FavoriteSourceState state, String conversationId, String messageId) { }
