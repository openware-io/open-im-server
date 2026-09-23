package io.openware.im.message.application.command;

import java.util.List;

public record MarkMessagesReadCommand(long userId, List<String> msgIds) {
}
