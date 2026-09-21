package com.gvchat.im.message.application.result;

import com.gvchat.common.enums.ChatType;
import com.gvchat.common.enums.MsgStatus;
import com.gvchat.common.enums.MsgType;
import java.time.LocalDateTime;
import java.util.List;
import com.gvchat.protocol.mq.event.MessageMedia;

public record MessageResult(Long id, String msgId, String conversationId, long seq, long fromUserId,
    String senderUsername, String toId, ChatType chatType, MsgType msgType, String content, String clientMsgId,
    String replyMsgId, List<String> atUsers, MsgStatus status, boolean edited, LocalDateTime editedAt, Long createdBy,
    LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt, List<MessageMedia> media) {
  public MessageResult(Long id, String msgId, String conversationId, long seq, long fromUserId,
      String senderUsername, String toId, ChatType chatType, MsgType msgType, String content, String clientMsgId,
      String replyMsgId, List<String> atUsers, MsgStatus status, Long createdBy, LocalDateTime createdAt,
      Long updatedBy, LocalDateTime updatedAt) {
    this(id, msgId, conversationId, seq, fromUserId, senderUsername, toId, chatType, msgType, content, clientMsgId,
        replyMsgId, atUsers, status, false, null, createdBy, createdAt, updatedBy, updatedAt, List.of());
  }
}
