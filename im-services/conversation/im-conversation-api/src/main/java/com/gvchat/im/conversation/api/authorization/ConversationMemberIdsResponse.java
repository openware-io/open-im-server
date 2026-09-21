package com.gvchat.im.conversation.api.authorization;

import java.util.List;

public record ConversationMemberIdsResponse(long conversationId, List<Long> userIds) {
}
