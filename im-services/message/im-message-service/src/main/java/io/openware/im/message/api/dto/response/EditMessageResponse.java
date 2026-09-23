package io.openware.im.message.api.dto.response;

import java.time.Instant;

/** 编辑消息响应：返回 edited 标记与编辑时间，供客户端更新本地渲染。 */
public record EditMessageResponse(String msgId, boolean edited, Instant editedAt) {
}
