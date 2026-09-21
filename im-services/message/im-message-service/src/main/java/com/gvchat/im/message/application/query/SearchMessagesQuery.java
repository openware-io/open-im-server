package com.gvchat.im.message.application.query;

import com.gvchat.common.enums.ChatType;
import com.gvchat.common.enums.MsgType;

public record SearchMessagesQuery(long userId, String keyword, String peerId, ChatType chatType, MsgType msgType,
    Integer page, Integer pageSize) { }
