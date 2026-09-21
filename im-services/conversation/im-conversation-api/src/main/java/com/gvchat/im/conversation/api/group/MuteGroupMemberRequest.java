package com.gvchat.im.conversation.api.group;

import jakarta.validation.constraints.NotNull;

public record MuteGroupMemberRequest(@NotNull Long userId, @NotNull Integer duration) {
}
