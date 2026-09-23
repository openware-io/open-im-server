package io.openware.im.message.api.dto.response;

import io.openware.common.enums.ChatType;

public record DeleteMessageResponse(boolean ok, String msgId, ChatType chatType, String toId, long fromUserId) { }
