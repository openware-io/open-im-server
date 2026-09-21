package com.gvchat.im.accessws.message;

import com.gvchat.protocol.mq.command.MessageRecallCommand;

public record PreparedRecallCommand(MessageRecallCommand command, CommandAcceptance acceptance, String shardingKey) {
}
