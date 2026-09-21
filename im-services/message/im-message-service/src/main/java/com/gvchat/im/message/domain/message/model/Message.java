package com.gvchat.im.message.domain.message.model;

import com.gvchat.common.enums.ChatType;
import com.gvchat.common.enums.MsgStatus;
import com.gvchat.common.enums.MsgType;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

public class Message {
  /** 消息可编辑窗口：发送后 2 分钟内允许发送者编辑正文（含已被对方读的消息，类似 Telegram）。 */
  public static final Duration EDIT_WINDOW = Duration.ofMinutes(2);

  private Long id;
  private final String msgId;
  private final String conversationId;
  private final long seq;
  private final long fromUserId;
  private final String senderUsername;
  private final String toId;
  private final ChatType chatType;
  private final MsgType msgType;
  private final String content;
  private final String clientMsgId;
  private final String replyMsgId;
  private final List<String> atUsers;
  private final List<String> mediaObjectIds;
  private final MsgStatus status;
  private final boolean edited;
  private final LocalDateTime editedAt;
  private final long createdBy;
  private final LocalDateTime createdAt;
  private final long updatedBy;
  private final LocalDateTime updatedAt;

  private Message(Long id, String msgId, String conversationId, long seq, long fromUserId,
      String senderUsername, String toId, ChatType chatType, MsgType msgType, String content,
      String clientMsgId, String replyMsgId, List<String> atUsers, MsgStatus status,
      List<String> mediaObjectIds, boolean edited, LocalDateTime editedAt, long createdBy, LocalDateTime createdAt,
      long updatedBy, LocalDateTime updatedAt) {
    this.id = id;
    this.msgId = msgId;
    this.conversationId = conversationId;
    this.seq = seq;
    this.fromUserId = fromUserId;
    this.senderUsername = senderUsername;
    this.toId = toId;
    this.chatType = chatType;
    this.msgType = msgType;
    this.content = content;
    this.clientMsgId = clientMsgId;
    this.replyMsgId = replyMsgId;
    this.atUsers = List.copyOf(atUsers);
    this.mediaObjectIds = List.copyOf(mediaObjectIds == null ? List.of() : mediaObjectIds);
    this.status = status;
    this.edited = edited;
    this.editedAt = editedAt;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedBy = updatedBy;
    this.updatedAt = updatedAt;
  }

  public static Message create(String msgId, String conversationId, long seq, long fromUserId,
      String senderUsername, String toId, ChatType chatType, MsgType msgType, String content,
      String clientMsgId, String replyMsgId, List<String> atUsers, LocalDateTime createdAt) {
    return create(msgId, conversationId, seq, fromUserId, senderUsername, toId, chatType, msgType, content,
        clientMsgId, replyMsgId, atUsers, List.of(), createdAt);
  }

  public static Message create(String msgId, String conversationId, long seq, long fromUserId,
      String senderUsername, String toId, ChatType chatType, MsgType msgType, String content,
      String clientMsgId, String replyMsgId, List<String> atUsers, List<String> mediaObjectIds, LocalDateTime createdAt) {
    return new Message(null, msgId, conversationId, seq, fromUserId, senderUsername, toId, chatType,
        msgType, content, clientMsgId, replyMsgId, atUsers, MsgStatus.SENT, mediaObjectIds, false, null, 0L, createdAt,
        0L, createdAt);
  }

  public static Message restore(Long id, String msgId, String conversationId, long seq, long fromUserId,
      String senderUsername, String toId, ChatType chatType, MsgType msgType, String content,
      String clientMsgId, String replyMsgId, List<String> atUsers, MsgStatus status,
      long createdBy, LocalDateTime createdAt, long updatedBy, LocalDateTime updatedAt) {
    return restore(id, msgId, conversationId, seq, fromUserId, senderUsername, toId, chatType, msgType, content,
        clientMsgId, replyMsgId, atUsers, List.of(), status, false, null, createdBy, createdAt, updatedBy, updatedAt);
  }

  public static Message restore(Long id, String msgId, String conversationId, long seq, long fromUserId,
      String senderUsername, String toId, ChatType chatType, MsgType msgType, String content,
      String clientMsgId, String replyMsgId, List<String> atUsers, List<String> mediaObjectIds, MsgStatus status,
      boolean edited, LocalDateTime editedAt, long createdBy, LocalDateTime createdAt, long updatedBy,
      LocalDateTime updatedAt) {
    return new Message(id, msgId, conversationId, seq, fromUserId, senderUsername, toId, chatType,
        msgType, content, clientMsgId, replyMsgId, atUsers, status, mediaObjectIds, edited, editedAt, createdBy,
        createdAt, updatedBy, updatedAt);
  }

  public Message recalled(LocalDateTime recalledAt) {
    return new Message(id, msgId, conversationId, seq, fromUserId, senderUsername, toId, chatType, MsgType.RECALL,
        "消息已撤回", clientMsgId, replyMsgId, List.of(), MsgStatus.RECALLED, List.of(), false, null, createdBy,
        createdAt, fromUserId, recalledAt);
  }

  /**
   * 发送后 2 分钟窗口内编辑正文，返回带 edited 标记的新消息（不可变实体，原对象不变）。
   * 权限校验（仅发送者本人）与窗口校验由应用层/本聚合的 {@link #editableAt} 完成；
   * 本方法只做状态迁移，不负责业务拒绝。
   */
  public Message edited(String newContent, long operatorId, LocalDateTime editedAt) {
    return new Message(id, msgId, conversationId, seq, fromUserId, senderUsername, toId, chatType, msgType, newContent,
        clientMsgId, replyMsgId, atUsers, status, mediaObjectIds, true, editedAt, createdBy, createdAt, operatorId,
        editedAt);
  }

  /** 编辑窗口是否仍有效：发送后 2 分钟内（含已被读消息）可编辑。 */
  public boolean editableAt(LocalDateTime now) {
    return createdAt != null && now != null && createdAt.plus(EDIT_WINDOW).isAfter(now);
  }

  public void assignId(Long id) { this.id = id; }
  public Long getId() { return id; }
  public String getMsgId() { return msgId; }
  public String getConversationId() { return conversationId; }
  public long getSeq() { return seq; }
  public long getFromUserId() { return fromUserId; }
  public String getSenderUsername() { return senderUsername; }
  public String getToId() { return toId; }
  public ChatType getChatType() { return chatType; }
  public MsgType getMsgType() { return msgType; }
  public String getContent() { return content; }
  public String getClientMsgId() { return clientMsgId; }
  public String getReplyMsgId() { return replyMsgId; }
  public List<String> getAtUsers() { return atUsers; }
  public List<String> getMediaObjectIds() { return mediaObjectIds; }
  public MsgStatus getStatus() { return status; }
  public boolean isEdited() { return edited; }
  public LocalDateTime getEditedAt() { return editedAt; }
  public long getCreatedBy() { return createdBy; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public long getUpdatedBy() { return updatedBy; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
}
