package com.gvchat.im.message.api.dto.response;

import com.gvchat.common.enums.ChatType;

public record DeleteMessageResponse(boolean ok, String msgId, ChatType chatType, String toId, long fromUserId) { }
