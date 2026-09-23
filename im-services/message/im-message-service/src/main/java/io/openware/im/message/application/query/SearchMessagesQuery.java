package io.openware.im.message.application.query;

import io.openware.common.enums.ChatType;
import io.openware.common.enums.MsgType;

public record SearchMessagesQuery(long userId, String keyword, String peerId, ChatType chatType, MsgType msgType,
    Integer page, Integer pageSize) { }
