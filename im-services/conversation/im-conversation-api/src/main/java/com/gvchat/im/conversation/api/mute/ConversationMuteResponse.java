package com.gvchat.im.conversation.api.mute;

/** 会话免打扰状态视图。 */
public record ConversationMuteResponse(String conversationId, boolean muted) {
}
