package io.openware.im.message.application.result;

import io.openware.common.enums.ChatType;

public record DeleteMessageResult(boolean ok, String msgId, ChatType chatType, String toId, long fromUserId) { }
