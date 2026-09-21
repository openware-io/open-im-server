package com.gvchat.im.conversation.api.group;

public record GroupMemberResponse(long userId, String nickname, String username, String role, String avatar) {
}
