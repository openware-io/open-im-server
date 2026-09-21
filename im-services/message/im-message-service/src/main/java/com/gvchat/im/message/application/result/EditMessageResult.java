package com.gvchat.im.message.application.result;

import java.time.Instant;

/** 编辑消息结果：返回编辑后的消息定位与 edited 标记，供客户端更新本地渲染。 */
public record EditMessageResult(String msgId, String conversationId, String chatType, String toId, long fromUserId,
    boolean edited, Instant editedAt) {
}
