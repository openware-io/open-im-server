package io.openware.im.message.domain.message.model;

import io.openware.common.enums.ChatType;
import io.openware.common.enums.MsgStatus;
import io.openware.common.enums.MsgType;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 普通消息聚合根。
 *
 * <p><b>为什么不带审计列</b>（standards/10 L299）：{@code createdBy}/{@code createdAt}/
 * {@code updatedBy}/{@code updatedAt} 是记账信息而非业务事实，由写路径在落库时确定
 * （适配器按端口 {@code save(..., occurredAt, operatorId)} 的参数写入）。
 * 响应装配、编辑窗口判定以及消息事件 / per-user 同步索引所需的发送时刻，经
 * {@code MessageRepository.Persisted} 的 {@code AuditSnapshot} 交给上层，而不是留在领域模型上。
 */
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

  private Message(Long id, String msgId, String conversationId, long seq, long fromUserId,
      String senderUsername, String toId, ChatType chatType, MsgType msgType, String content,
      String clientMsgId, String replyMsgId, List<String> atUsers, MsgStatus status,
      List<String> mediaObjectIds, boolean edited, LocalDateTime editedAt) {
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
  }

  public static Message create(String msgId, String conversationId, long seq, long fromUserId,
      String senderUsername, String toId, ChatType chatType, MsgType msgType, String content,
      String clientMsgId, String replyMsgId, List<String> atUsers) {
    return create(msgId, conversationId, seq, fromUserId, senderUsername, toId, chatType, msgType, content,
        clientMsgId, replyMsgId, atUsers, List.of());
  }

  public static Message create(String msgId, String conversationId, long seq, long fromUserId,
      String senderUsername, String toId, ChatType chatType, MsgType msgType, String content,
      String clientMsgId, String replyMsgId, List<String> atUsers, List<String> mediaObjectIds) {
    return new Message(null, msgId, conversationId, seq, fromUserId, senderUsername, toId, chatType,
        msgType, content, clientMsgId, replyMsgId, atUsers, MsgStatus.SENT, mediaObjectIds, false, null);
  }

  public static Message restore(Long id, String msgId, String conversationId, long seq, long fromUserId,
      String senderUsername, String toId, ChatType chatType, MsgType msgType, String content,
      String clientMsgId, String replyMsgId, List<String> atUsers, MsgStatus status) {
    return restore(id, msgId, conversationId, seq, fromUserId, senderUsername, toId, chatType, msgType, content,
        clientMsgId, replyMsgId, atUsers, List.of(), status, false, null);
  }

  /** 由持久化对象重建领域对象（仅适配器使用）。 */
  public static Message restore(Long id, String msgId, String conversationId, long seq, long fromUserId,
      String senderUsername, String toId, ChatType chatType, MsgType msgType, String content,
      String clientMsgId, String replyMsgId, List<String> atUsers, List<String> mediaObjectIds, MsgStatus status,
      boolean edited, LocalDateTime editedAt) {
    return new Message(id, msgId, conversationId, seq, fromUserId, senderUsername, toId, chatType,
        msgType, content, clientMsgId, replyMsgId, atUsers, status, mediaObjectIds, edited, editedAt);
  }

  /**
   * 发送后 2 分钟窗口内编辑正文，返回带 edited 标记的新消息（不可变实体，原对象不变）。
   * 权限校验（仅发送者本人）与窗口校验由应用层/本聚合的 {@link #editableAt(LocalDateTime, LocalDateTime)} 完成；
   * 本方法只做状态迁移，不负责业务拒绝。{@code editedAt} 是业务字段（客户端展示编辑时刻），
   * 记账列 {@code updatedAt}/{@code updatedBy} 由端口 {@code save(..., occurredAt, operatorId)} 写入。
   */
  public Message edited(String newContent, LocalDateTime editedAt) {
    return new Message(id, msgId, conversationId, seq, fromUserId, senderUsername, toId, chatType, msgType, newContent,
        clientMsgId, replyMsgId, atUsers, status, mediaObjectIds, true, editedAt);
  }

  /**
   * 编辑窗口是否仍有效：**发送后 2 分钟内**（含已被读消息）可编辑。
   *
   * <p><b>为什么是静态纯函数</b>（standards/10 L299）：这条不变量需要读「发送时刻」，而
   * {@code createdAt} 是记账列、不属于领域模型。把发送时刻作为**参数**传入，两件事同时成立：
   * 规则留在领域层，字段不在领域模型。调用方从 {@code MessageRepository.Persisted.audit().createdAt()}
   * 取得发送时刻。
   *
   * <p><b>边界语义</b>：{@code sentAt + 2min} 用 {@link LocalDateTime#isAfter} 比较，
   * 因此**恰好等于截止时刻不可编辑**。该边界由 {@code MessageTest} 钉住——把它换成
   * {@code !isBefore} 会让这一刻变成可编辑，而两侧的常规断言仍会通过。
   */
  public static boolean editableAt(LocalDateTime sentAt, LocalDateTime now) {
    return sentAt != null && now != null && sentAt.plus(EDIT_WINDOW).isAfter(now);
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
}
