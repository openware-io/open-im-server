package com.gvchat.im.message.application.command;

/** 编辑消息正文命令：仅发送者本人、发送后 2 分钟内可编辑（含已被读消息）。 */
public record EditMessageCommand(long userId, String msgId, String newContent) {
}
