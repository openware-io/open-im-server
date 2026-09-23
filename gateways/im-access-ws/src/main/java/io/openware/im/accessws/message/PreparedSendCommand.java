package io.openware.im.accessws.message;

import io.openware.protocol.mq.command.MessageSendCommand;

public record PreparedSendCommand(
    MessageSendCommand command,
    MessageAcceptance acceptance,
    String shardingKey) {
}
