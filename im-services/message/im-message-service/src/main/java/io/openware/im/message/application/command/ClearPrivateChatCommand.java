package io.openware.im.message.application.command;

public record ClearPrivateChatCommand(long userId, String peerId) { }
