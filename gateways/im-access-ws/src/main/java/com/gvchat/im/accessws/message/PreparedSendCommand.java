package com.gvchat.im.accessws.message;

import com.gvchat.protocol.mq.command.MessageSendCommand;

public record PreparedSendCommand(
    MessageSendCommand command,
    MessageAcceptance acceptance,
    String shardingKey) {
}
