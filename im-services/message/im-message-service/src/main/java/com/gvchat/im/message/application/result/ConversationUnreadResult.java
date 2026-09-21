package com.gvchat.im.message.application.result;

/** 按会话未读数（服务端权威，直接从 MySQL 聚合）。 */
public record ConversationUnreadResult(String conversationId, long count) {
}
