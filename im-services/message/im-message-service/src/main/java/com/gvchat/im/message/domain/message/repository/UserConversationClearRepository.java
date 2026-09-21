package com.gvchat.im.message.domain.message.repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话清空标记仓储（per-user）。
 *
 * <p>存在的意义：实时 WS 通知在对方**离线**时会丢失，而服务端消息已删除、
 * 增量同步又不会告知删除，导致对方本地旧记录残留。
 * 把清空落成持久标记后，客户端每次同步都能据此自愈（离线/换端/重装均覆盖）。
 */
public interface UserConversationClearRepository {

  /** 记录某用户在会话上的清空时刻（重复清空取最新）。 */
  void markCleared(long userId, String conversationId, String chatType, LocalDateTime clearedAt, long clearedBy);

  /** 该用户名下全部清空标记。 */
  List<ClearedConversation> findByUserId(long userId);

  /** 清空标记视图。 */
  record ClearedConversation(String conversationId, String chatType, LocalDateTime clearedAt) {
  }
}
