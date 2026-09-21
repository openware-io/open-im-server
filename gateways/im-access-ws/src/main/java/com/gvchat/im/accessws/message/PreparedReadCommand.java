package com.gvchat.im.accessws.message;

import com.gvchat.protocol.mq.command.MessageReadCommand;

public record PreparedReadCommand(MessageReadCommand command, CommandAcceptance acceptance, String shardingKey) {
}
