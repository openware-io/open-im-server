package io.openware.im.user.application.social.command;

public record SendFriendRequestCommand(Long toUserId, String message, String source, Long groupId) {
  public SendFriendRequestCommand(Long toUserId, String message) {
    this(toUserId, message, null, null);
  }
}
