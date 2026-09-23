package io.openware.im.message.application.favorite.command;

import io.openware.common.enums.ChatType;

public record AddFavoriteCommand(long userId, String msgId, String peerId, ChatType chatType) { }
