package io.openware.im.accessws.message;

import io.openware.protocol.mq.command.MessageRecallCommand;

public record PreparedRecallCommand(MessageRecallCommand command, CommandAcceptance acceptance, String shardingKey) {
}
