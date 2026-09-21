package com.gvchat.im.message.domain.message.repository;

import java.util.List;

/**
 * 用户「删除仅我」墓碑仓储：把「该用户删过哪些消息」持久化，使删除结果跨重装保留。
 *
 * <p>语义边界：仅影响调用者自己的可见性，不影响消息本体、其它成员的可见性，
 * 也不改变消息在会话中的顺序（不删除 msg_message 行）。
 */
public interface UserDeletedMessageRepository {

  /** 幂等记录（同一用户重复删除同一条消息不报错）。 */
  void markDeleted(long userId, String msgId, String conversationId, String chatType);

  /** 批量记录，返回实际新增条数。 */
  int markDeleted(long userId, List<String> msgIds);

  /**
   * 该用户名下全部「删除仅我」墓碑 msgId（按删除时间倒序，上限 2000）。
   *
   * <p>用途：同步时下发，让**同一账号的其它设备**也能删除本地副本。
   * 仅靠「同步排除」不够 —— 那只能阻止重新插入，无法清掉设备上已存在的旧数据。
   */
  List<DeletedMessage> findAllByUserId(long userId);

  /** 「删除仅我」墓碑（含会话定位信息，供客户端清理本地副本与会话预览）。 */
  record DeletedMessage(String msgId, String conversationId, String chatType) {
  }

  /** 在给定消息集合中，返回该用户已标记删除的 msgId（供同步/历史/未读过滤）。 */
  List<String> findDeletedMsgIds(long userId, List<String> msgIds);

  /** 清空会话时回收该会话下的墓碑记录（消息本体已删，墓碑无意义）。 */
  void deleteByConversation(long userId, String conversationId);
}
