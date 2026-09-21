package com.gvchat.im.conversation.api.mute;

import jakarta.validation.constraints.NotNull;

/** 会话免打扰开关请求：{@code muted=true} 开启免打扰，{@code false} 取消。 */
public record MuteConversationRequest(@NotNull Boolean muted) {
}
