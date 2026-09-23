package io.openware.im.accessws.message;

import io.openware.protocol.mq.command.MessageReadCommand;

public record PreparedReadCommand(MessageReadCommand command, CommandAcceptance acceptance, String shardingKey) {
}
