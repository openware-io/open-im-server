package io.openware.im.message.infra.persistence.message.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.common.enums.ChatType;
import io.openware.common.enums.MsgType;
import io.openware.im.message.domain.message.model.Message;
import io.openware.im.message.domain.message.repository.MessageRepository;
import io.openware.im.message.infra.persistence.message.MessagePersistenceConverter;
import io.openware.im.message.infra.persistence.message.mapper.MessageMapper;
import io.openware.im.message.infra.persistence.message.po.MessagePo;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j
public class MessageRepositoryAdapter implements MessageRepository {
  private final MessageMapper mapper;

  @Override
  public Optional<Message> findByMsgId(String msgId) {
    return Optional.ofNullable(mapper.selectOne(Wrappers.<MessagePo>lambdaQuery()
        .eq(MessagePo::getMsgId, msgId).last("LIMIT 1"))).map(MessagePersistenceConverter::toDomain);
  }

  @Override
  public Optional<Message> findBySenderIdAndClientMsgId(long senderId, String clientMsgId) {
    return Optional.ofNullable(mapper.selectOne(Wrappers.<MessagePo>lambdaQuery()
        .eq(MessagePo::getFromUserId, senderId)
        .eq(MessagePo::getClientMsgId, clientMsgId)
        .last("LIMIT 1"))).map(MessagePersistenceConverter::toDomain);
  }

  @Override
  public List<Message> findByMsgIds(List<String> msgIds) {
    if (msgIds == null || msgIds.isEmpty()) return List.of();
    return mapper.selectList(Wrappers.<MessagePo>lambdaQuery().in(MessagePo::getMsgId, msgIds))
        .stream().map(MessagePersistenceConverter::toDomain).toList();
  }

  @Override
  public Message save(Message message) {
    MessagePo po = MessagePersistenceConverter.toPo(message);
    mapper.insert(po);
    message.assignId(po.getId());
    return message;
  }

  @Override
  public Message update(Message message) {
    mapper.updateById(MessagePersistenceConverter.toPo(message));
    return message;
  }

  @Override
  public List<Message> findHistory(long userId, String peerId, ChatType chatType, int page, int pageSize) {
    return mapper.selectPage(new Page<MessagePo>(page, pageSize), Wrappers.<MessagePo>lambdaQuery()
            .eq(MessagePo::getChatType, chatType)
            .and(wrapper -> visibleToUser(wrapper, userId, peerId, chatType))
            .orderByDesc(MessagePo::getCreatedAt))
        .getRecords().stream().map(MessagePersistenceConverter::toDomain).toList();
  }

  @Override
  public List<String> findHistoryDates(long userId, String peerId, ChatType chatType) {
    List<MessagePo> rows = mapper.selectList(Wrappers.<MessagePo>lambdaQuery()
        .select(MessagePo::getCreatedAt)
        .eq(MessagePo::getChatType, chatType)
        .and(wrapper -> visibleToUser(wrapper, userId, peerId, chatType)));
    return rows.stream()
        .map(MessagePo::getCreatedAt)
        .filter(Objects::nonNull)
        .map(LocalDateTime::toLocalDate)
        .distinct()
        .sorted(Comparator.reverseOrder())
        .map(LocalDate::toString)
        .toList();
  }

  @Override
  public List<Message> findHistoryByDate(long userId, String peerId, ChatType chatType, LocalDate date, int page,
      int pageSize) {
    return mapper.selectPage(new Page<MessagePo>(page, pageSize), Wrappers.<MessagePo>lambdaQuery()
            .eq(MessagePo::getChatType, chatType)
            .and(wrapper -> visibleToUser(wrapper, userId, peerId, chatType))
            .ge(MessagePo::getCreatedAt, date.atStartOfDay())
            .lt(MessagePo::getCreatedAt, date.plusDays(1).atStartOfDay())
            .orderByDesc(MessagePo::getCreatedAt))
        .getRecords().stream().map(MessagePersistenceConverter::toDomain).toList();
  }

  @Override
  public List<Message> findCentered(long userId, String peerId, ChatType chatType, String centerMsgId,
      int beforeCount, int afterCount) {
    MessagePo center = mapper.selectOne(Wrappers.<MessagePo>lambdaQuery()
        .eq(MessagePo::getMsgId, centerMsgId)
        .eq(MessagePo::getChatType, chatType)
        .and(wrapper -> visibleToUser(wrapper, userId, peerId, chatType))
        .last("LIMIT 1"));
    if (center == null) {
      return List.of();
    }
    String conversationId = center.getConversationId();
    long centerSeq = center.getSeq();
    List<MessagePo> before = mapper.selectList(Wrappers.<MessagePo>lambdaQuery()
        .eq(MessagePo::getConversationId, conversationId)
        .lt(MessagePo::getSeq, centerSeq)
        .orderByDesc(MessagePo::getSeq)
        .last("LIMIT " + Math.max(0, beforeCount)));
    List<MessagePo> after = mapper.selectList(Wrappers.<MessagePo>lambdaQuery()
        .eq(MessagePo::getConversationId, conversationId)
        .gt(MessagePo::getSeq, centerSeq)
        .orderByAsc(MessagePo::getSeq)
        .last("LIMIT " + Math.max(0, afterCount)));
    List<MessagePo> merged = new java.util.ArrayList<>(before.size() + 1 + after.size());
    java.util.Collections.reverse(before);
    merged.addAll(before);
    merged.add(center);
    merged.addAll(after);
    return merged.stream().map(MessagePersistenceConverter::toDomain).toList();
  }

  @Override
  public List<Message> findChannelMessages(String conversationId, long afterSeq, int limit) {
    return mapper.selectList(Wrappers.<MessagePo>lambdaQuery()
            .eq(MessagePo::getConversationId, conversationId)
            .gt(MessagePo::getSeq, afterSeq)
            .orderByAsc(MessagePo::getSeq)
            .last("LIMIT " + Math.min(limit <= 0 ? 50 : limit, 100)))
        .stream().map(MessagePersistenceConverter::toDomain).toList();
  }

  @Override
  public MessagePage search(long userId, String keyword, String peerId, ChatType chatType, MsgType msgType,
      int page, int pageSize) {
    Page<MessagePo> result = mapper.selectPage(new Page<>(page, pageSize), Wrappers.<MessagePo>lambdaQuery()
        .like(keyword != null && !keyword.isBlank(), MessagePo::getContent, keyword)
        .eq(chatType != null, MessagePo::getChatType, chatType)
        .eq(msgType != null, MessagePo::getMsgType, msgType)
        .and(peerId != null && chatType != null, wrapper -> visibleToUser(wrapper, userId, peerId, chatType))
        .orderByDesc(MessagePo::getCreatedAt));
    return new MessagePage(result.getRecords().stream().map(MessagePersistenceConverter::toDomain).toList(), result.getTotal());
  }

  @Override
  public List<Message> findAdminPage(int page, int pageSize) {
    // 管理端列表只按创建时间倒序，配合 idx_msg_message_created 索引走索引排序，
    // 追加 id DESC 作为确定性 tie-break（created_at 为毫秒精度，可能重复）。
    // searchCount=false 跳过 COUNT(*)（总数由上层缓存），仅查本页数据。
    Page<MessagePo> result = mapper.selectPage(new Page<>(page, pageSize, false), Wrappers.<MessagePo>lambdaQuery()
        .orderByDesc(MessagePo::getCreatedAt).orderByDesc(MessagePo::getId));
    return result.getRecords().stream().map(MessagePersistenceConverter::toDomain).toList();
  }

  @Override
  public long count() {
    return mapper.selectCount(null);
  }

  @Override
  public long countCreatedSince(LocalDateTime createdAt) {
    return mapper.selectCount(Wrappers.<MessagePo>lambdaQuery().ge(MessagePo::getCreatedAt, createdAt));
  }

  @Override
  public List<Map<String, Object>> dailyCounts(LocalDateTime since) {
    QueryWrapper<MessagePo> wrapper = new QueryWrapper<MessagePo>()
        .select("DATE_FORMAT(created_at, '%Y-%m-%d') AS date", "COUNT(*) AS count")
        .ge("created_at", since)
        .groupBy("DATE_FORMAT(created_at, '%Y-%m-%d')")
        .orderByAsc("date");
    return mapper.selectMaps(wrapper);
  }

  @Override
  public long countDistinctSendersSince(LocalDateTime since) {
    QueryWrapper<MessagePo> wrapper = new QueryWrapper<MessagePo>()
        .select("COUNT(DISTINCT from_user_id) AS cnt")
        .ge("created_at", since);
    List<Map<String, Object>> rows = mapper.selectMaps(wrapper);
    if (rows == null || rows.isEmpty()) return 0;
    Object cnt = rows.get(0).get("cnt");
    return cnt == null ? 0 : Long.parseLong(cnt.toString());
  }

  @Override
  public long countUnreadForUser(long userId, boolean privateEnabled, boolean groupEnabled, boolean channelEnabled) {
    return mapper.countUnreadForUser(userId, privateEnabled, groupEnabled, channelEnabled);
  }

  @Override
  public List<ConversationUnread> countUnreadByConversationForUser(long userId, boolean privateEnabled,
      boolean groupEnabled, boolean channelEnabled) {
    List<Map<String, Object>> rows = mapper.countUnreadByConversationForUser(userId, privateEnabled, groupEnabled,
        channelEnabled);
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    return rows.stream()
        .map(row -> new ConversationUnread(String.valueOf(row.get("conversation_id")),
            row.get("cnt") == null ? 0L : Long.parseLong(row.get("cnt").toString())))
        .toList();
  }

  @Override
  public void deletePrivateChat(long userId, long peerUserId) {
    // 先清关联行（同步索引 / 已读状态 / 收藏）——子查询依赖消息仍存在，否则会留下孤儿行。
    int readRows = mapper.deleteReadStatusInPrivateChat(userId, peerUserId);
    int indexRows = mapper.deleteSyncIndexInPrivateChat(userId, peerUserId);
    int favoriteRows = mapper.deleteFavoriteInPrivateChat(userId, peerUserId);
    int messageRows = mapper.deleteMessagesInPrivateChat(userId, peerUserId);
    log.info("清空私聊删除结果, userId={}, peerUserId={}, read={}, index={}, favorite={}, message={}",
        userId, peerUserId, readRows, indexRows, favoriteRows, messageRows);
  }

  @Override
  public void deleteGroupChat(long groupId) {
    // 同上：先清关联行，再删消息本体。
    int readRows = mapper.deleteReadStatusInGroupChat(groupId);
    int indexRows = mapper.deleteSyncIndexInGroupChat(groupId);
    int favoriteRows = mapper.deleteFavoriteInGroupChat(groupId);
    int messageRows = mapper.deleteMessagesInGroupChat(groupId);
    log.info("清空群聊删除结果, groupId={}, read={}, index={}, favorite={}, message={}",
        groupId, readRows, indexRows, favoriteRows, messageRows);
  }

  @Override
  public void deleteByMsgId(String msgId) {
    mapper.delete(Wrappers.<MessagePo>lambdaQuery().eq(MessagePo::getMsgId, msgId));
  }

  private void visibleToUser(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MessagePo> wrapper,
      long userId, String peerId, ChatType chatType) {
    // 「删除仅我」过滤：历史、按日期、上下文窗口、搜索共用此助手，因此在这里加一次即可全覆盖，
    // 避免出现「会话内删了、搜索结果还能搜到」这类不一致。
    wrapper.notExists(
        "SELECT 1 FROM msg_user_deleted_message d WHERE d.user_id = {0} AND d.msg_id = msg_message.msg_id",
        userId);
    if (chatType == ChatType.PRIVATE) {
      wrapper.and(inner -> inner
          .eq(MessagePo::getFromUserId, userId).eq(MessagePo::getToId, peerId)
          .or(item -> item.eq(MessagePo::getFromUserId, Long.parseLong(peerId))
              .eq(MessagePo::getToId, String.valueOf(userId))));
      return;
    }
    wrapper.eq(MessagePo::getToId, peerId);
  }
}
