package io.openware.im.message.application.command;

public record DeleteMessageCommand(long userId, String msgId, boolean recall) {
  public DeleteMessageCommand(long userId, String msgId) {
    this(userId, msgId, false);
  }
}
