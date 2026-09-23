package io.openware.im.message.api.dto.response;

/**
 * 批量收藏单条结果：created=false 表示该条未新建（已收藏过、消息不存在或无权访问）。
 */
public record FavoriteBatchItemResponse(String messageId, boolean created) { }
