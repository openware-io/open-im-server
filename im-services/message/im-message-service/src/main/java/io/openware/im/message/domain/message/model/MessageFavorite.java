package io.openware.im.message.domain.message.model;

import io.openware.common.enums.ChatType;
import io.openware.common.enums.MsgType;
import java.time.LocalDateTime;

/**
 * 用户消息收藏：记录 msgId + 会话定位信息（peerId/chatType），并携带一份「收藏时」的内容快照。
 *
 * <p>读取时优先回查权威消息（{@code msg_message}），保证能看到编辑后的最新内容；权威消息已被
 * 撤回/删除，或当前用户已无权访问时，回落到收藏时写入的快照，落实「收藏内容永远可读」。
 * 快照列（V13）之前落库的历史行快照为空，读取侧仍按原有占位行为渲染。
 *
 * <p><b>本实体不带收藏时刻</b>（standards/10 L299：审计列不属于领域模型）。列表要按收藏时刻倒序展示，
 * 该时刻由只读投影 {@link io.openware.im.message.domain.message.readmodel.MessageFavoriteView} 携带
 * （standards/10 L266-279：列表这类只读聚合查询用投影建模，不与写路径的领域模型混用）。
 */
public class MessageFavorite {
  private Long id;
  private final long userId;
  private final String msgId;
  private final String peerId;
  private final ChatType chatType;
  /** 收藏时原消息的类型快照；历史行与密聊收藏为空。 */
  private final MsgType msgTypeSnapshot;
  /** 收藏时原消息的正文快照。 */
  private final String contentSnapshot;
  private final Long fromUserIdSnapshot;
  private final String senderUsernameSnapshot;
  /** 收藏时原消息的发送时间快照（非收藏时间）。 */
  private final LocalDateTime sentAtSnapshot;

  private MessageFavorite(Long id, long userId, String msgId, String peerId, ChatType chatType,
      MsgType msgTypeSnapshot, String contentSnapshot, Long fromUserIdSnapshot,
      String senderUsernameSnapshot, LocalDateTime sentAtSnapshot) {
    this.id = id;
    this.userId = userId;
    this.msgId = msgId;
    this.peerId = peerId;
    this.chatType = chatType;
    this.msgTypeSnapshot = msgTypeSnapshot;
    this.contentSnapshot = contentSnapshot;
    this.fromUserIdSnapshot = fromUserIdSnapshot;
    this.senderUsernameSnapshot = senderUsernameSnapshot;
    this.sentAtSnapshot = sentAtSnapshot;
  }

  /** 不带快照的收藏（密聊等无正文快照场景，以及历史数据）。 */
  public static MessageFavorite create(long userId, String msgId, String peerId, ChatType chatType) {
    return create(userId, msgId, peerId, chatType, null, null, null, null, null);
  }

  /** 带收藏时内容快照的收藏。 */
  public static MessageFavorite create(long userId, String msgId, String peerId, ChatType chatType,
      MsgType msgTypeSnapshot, String contentSnapshot, Long fromUserIdSnapshot,
      String senderUsernameSnapshot, LocalDateTime sentAtSnapshot) {
    return new MessageFavorite(null, userId, msgId, peerId, chatType, msgTypeSnapshot, contentSnapshot,
        fromUserIdSnapshot, senderUsernameSnapshot, sentAtSnapshot);
  }

  /** 由持久化对象重建领域对象（仅适配器使用）。 */
  public static MessageFavorite restore(Long id, long userId, String msgId, String peerId, ChatType chatType,
      MsgType msgTypeSnapshot, String contentSnapshot, Long fromUserIdSnapshot,
      String senderUsernameSnapshot, LocalDateTime sentAtSnapshot) {
    return new MessageFavorite(id, userId, msgId, peerId, chatType, msgTypeSnapshot, contentSnapshot,
        fromUserIdSnapshot, senderUsernameSnapshot, sentAtSnapshot);
  }

  /** 是否带收藏时快照：任一快照列非空即视为有；V13 之前的历史行全为空。 */
  public boolean hasSnapshot() {
    return msgTypeSnapshot != null || contentSnapshot != null || fromUserIdSnapshot != null
        || senderUsernameSnapshot != null || sentAtSnapshot != null;
  }

  public void assignId(Long id) { this.id = id; }
  public Long getId() { return id; }
  public long getUserId() { return userId; }
  public String getMsgId() { return msgId; }
  public String getPeerId() { return peerId; }
  public ChatType getChatType() { return chatType; }
  public MsgType getMsgTypeSnapshot() { return msgTypeSnapshot; }
  public String getContentSnapshot() { return contentSnapshot; }
  public Long getFromUserIdSnapshot() { return fromUserIdSnapshot; }
  public String getSenderUsernameSnapshot() { return senderUsernameSnapshot; }
  public LocalDateTime getSentAtSnapshot() { return sentAtSnapshot; }
}
