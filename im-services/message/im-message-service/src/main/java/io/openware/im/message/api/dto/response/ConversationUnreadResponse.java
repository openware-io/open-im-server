package io.openware.im.message.api.dto.response;

/** 按会话未读数（供客户端角标按会话展示）。 */
public record ConversationUnreadResponse(String conversationId, long count) {
}
