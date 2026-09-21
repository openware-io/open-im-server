package com.gvchat.im.message.application.result;

import com.gvchat.common.enums.ChatType;

public record DeleteMessageResult(boolean ok, String msgId, ChatType chatType, String toId, long fromUserId) { }
