package io.openware.im.message.domain.message.repository;

import io.openware.im.message.domain.message.model.Message;
import io.openware.im.message.domain.message.readmodel.MessageView;
import io.openware.common.enums.ChatType;
import io.openware.common.enums.MsgType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 消息仓储端口。
 *
 * <p><b>写路径返回聚合与记账快照，读路径返回投影</b>（standards/10 L266-300）：
 * 单条、批量读取与写入返回 {@link Persisted}，让响应装配与编辑窗口判定拿到审计列，
 * 而不必把它们留在领域模型上；四条列表查询（历史、按日期、上下文窗口、频道游标）与两个分页查询
 * （搜索、管理端）是只读聚合查询、结果不参与状态变更决策，返回 {@link MessageView} 投影——
 * 投影携带展示所需的审计列，排序留在适配器。
 *
 * <p><b>{@code save} 的语义统一为 {@code id == null} 新增、否则更新</b>（L300），因此不再保留独立的
 * {@code update}：新建与编辑走同一端口，调用方按业务意图选择，而不是按技术动作选择。
 */
public interface MessageRepository {
  Optional<Persisted<Message>> findByMsgId(String msgId);
  Optional<Persisted<Message>> findBySenderIdAndClientMsgId(long senderId, String clientMsgId);
  List<Persisted<Message>> findByMsgIds(List<String> msgIds);

  /**
   * 新增或更新（{@code id == null} 新增），返回落库后的聚合与记账快照。
   *
   * <p><b>审计列由调用方给出写入时刻</b>（standards/10 L299）：新增时 {@code occurredAt} 同时写
   * {@code created_at}/{@code updated_at}，更新时只写 {@code updated_at}；{@code operatorId} 写
   * {@code created_by}/{@code updated_by}（新增）或只写 {@code updated_by}（更新）。
   * 适配器不得自行打 {@code now()}——新建消息的发送时刻来自命令的 {@code acceptedAt}，
   * 消息事件与 per-user 同步索引都以它为准，自行取时会让三者漂移。
   */
  Persisted<Message> save(Message message, LocalDateTime occurredAt, long operatorId);

  List<MessageView> findHistory(long userId, String peerId, ChatType chatType, int page, int pageSize);

  /** 会话内「有消息」的日期（yyyy-MM-dd，UTC，倒序），用于日历标记。 */
  List<String> findHistoryDates(long userId, String peerId, ChatType chatType);

  /** 会话内某一天（UTC）的消息历史分页。 */
  List<MessageView> findHistoryByDate(long userId, String peerId, ChatType chatType, LocalDate date, int page,
      int pageSize);

  /**
   * 以 [centerMsgId] 为中心返回前后窗口（含中心消息，按时间升序）。
   * 中心消息不存在或用户无权访问时返回空列表。
   */
  List<MessageView> findCentered(long userId, String peerId, ChatType chatType, String centerMsgId,
      int beforeCount, int afterCount);
  List<MessageView> findChannelMessages(String conversationId, long afterSeq, int limit);
  MessagePage search(long userId, String keyword, String peerId, ChatType chatType, MsgType msgType, int page, int pageSize);
  /** 管理端分页（只查本页数据，不统计总数；总数由调用方经缓存获取）。 */
  List<MessageView> findAdminPage(int page, int pageSize);
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

  record MessagePage(List<MessageView> items, long total) { }

  record ConversationUnread(String conversationId, long count) { }
}
