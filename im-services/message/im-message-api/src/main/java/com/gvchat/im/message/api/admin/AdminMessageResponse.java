package com.gvchat.im.message.api.admin;

import com.gvchat.common.enums.ChatType;
import com.gvchat.common.enums.MsgStatus;
import com.gvchat.common.enums.MsgType;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminMessageResponse {
  private final Long id;
  private final String msgId;
  private final Long fromUserId;
  private final String toId;
  private final ChatType chatType;
  private final MsgType msgType;
  private final String content;
  private final String clientMsgId;
  private final String replyMsgId;
  private final List<String> atUsers;
  private final MsgStatus status;
  private final Instant createdAt;
}
