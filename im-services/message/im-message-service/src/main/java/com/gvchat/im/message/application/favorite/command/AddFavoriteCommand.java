package com.gvchat.im.message.application.favorite.command;

import com.gvchat.common.enums.ChatType;

public record AddFavoriteCommand(long userId, String msgId, String peerId, ChatType chatType) { }
