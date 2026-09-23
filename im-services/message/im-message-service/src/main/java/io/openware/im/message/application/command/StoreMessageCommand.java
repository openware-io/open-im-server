package io.openware.im.message.application.command;

import java.time.Instant;
import java.util.List;

public record StoreMessageCommand(String commandId, String conversationId, long senderId,
    String senderUsername, String clientMsgId, String chatType, String toId, String msgType,
    String content, String replyMsgId, String atUsersJson, Instant acceptedAt, List<String> mediaObjectIds) {
  public StoreMessageCommand(String commandId, String conversationId, long senderId, String senderUsername,
      String clientMsgId, String chatType, String toId, String msgType, String content, String replyMsgId,
      String atUsersJson, Instant acceptedAt) {
    this(commandId, conversationId, senderId, senderUsername, clientMsgId, chatType, toId, msgType, content,
        replyMsgId, atUsersJson, acceptedAt, List.of());
  }
}
