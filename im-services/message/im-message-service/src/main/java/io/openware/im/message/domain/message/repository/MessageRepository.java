package io.openware.im.message.domain.message.repository;

import io.openware.im.message.domain.message.model.Message;
import io.openware.common.enums.ChatType;
import io.openware.common.enums.MsgType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface MessageRepository {
  Optional<Message> findByMsgId(String msgId);
  Optional<Message> findBySenderIdAndClientMsgId(long senderId, String clientMsgId);
  List<Message> findByMsgIds(List<String> msgIds);
  Message save(Message message);
  Message update(Message message);
  List<Message> findHistory(long userId, String peerId, ChatType chatType, int page, int pageSize);

  /** 会话内「有消息」的日期（yyyy-MM-dd，UTC，倒序），用于日历标记。 */
  List<String> findHistoryDates(long userId, String peerId, ChatType chatType);

  /** 会话内某一天（UTC）的消息历史分页。 */
  List<Message> findHistoryByDate(long userId, String peerId, ChatType chatType, LocalDate date, int page, int pageSize);

  /**
   * 以 [centerMsgId] 为中心返回前后窗口（含中心消息，按时间升序）。
   * 中心消息不存在或用户无权访问时返回空列表。
   */
  List<Message> findCentered(long userId, String peerId, ChatType chatType, String centerMsgId,
      int beforeCount, int afterCount);
  List<Message> findChannelMessages(String conversationId, long afterSeq, int limit);
  MessagePage search(long userId, String keyword, String peerId, ChatType chatType, MsgType msgType, int page, int pageSize);
  /** 管理端分页（只查本页数据，不统计总数；总数由调用方经缓存获取）。 */
  List<Message> findAdminPage(int page, int pageSize);
  long count();
  long countCreatedSince(LocalDateTime createdAt);
  /** 按天统计消息量（返回 [{date:'yyyy-MM-dd', count:N}]，按日期升序），用于趋势图。 */
  List<Map<String, Object>> dailyCounts(LocalDateTime since);
  /** 统计 [since] 以来发过消息的去重用户数（DAU）。 */
  long countDistinctSendersSince(LocalDateTime since);
  long countUnreadForUser(long userId, boolean privateEnabled, boolean groupEnabled, boolean channelEnabled);
  /** 按会话聚合的用户未读数（MySQL 权威），供客户端角标按会话展示。 */
  List<ConversationUnread> countUnreadByConversationForUser(long userId, boolean privateEnabled, boolean groupEnabled,
      boolean channelEnabled);
  void deletePrivateChat(long userId, long peerUserId);
  void deleteGroupChat(long groupId);
  void deleteByMsgId(String msgId);

  record MessagePage(List<Message> items, long total) { }

  record ConversationUnread(String conversationId, long count) { }
}
