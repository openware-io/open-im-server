package com.gvchat.im.conversation.api.authorization;

import java.util.List;

public record GroupMessageRecipientSnapshot(
    GroupMessageAuthorizationSnapshot authorization,
    List<Long> memberUserIds) {
}
