package com.gvchat.protocol.mq.command;

import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageSendCommand {
  private String commandId;
  private String conversationId;
  private long senderId;
  private String senderUsername;
  private String clientMsgId;
  private String chatType;
  private String toId;
  private String msgType;
  private String content;
  private String replyMsgId;
  private String atUsersJson;
  private List<String> mediaObjectIds;
  private Instant acceptedAt;
}
