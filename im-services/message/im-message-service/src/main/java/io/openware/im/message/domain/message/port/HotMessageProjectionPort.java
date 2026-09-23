package io.openware.im.message.domain.message.port;

/**
 * 热消息投影（MongoDB `msg_hot_message`）的清理端口。
 *
 * <p>存在的意义：热消息投影此前**只写不清**——消息被撤回/删除、会话被清空后，
 * MongoDB 里的热消息仍然保留（30 天 TTL 才消失），与 MySQL 权威数据不一致。
 * 一旦将来有读取方（历史加速、搜索、离线回捞）就会读到已删除的内容。
 */
public interface HotMessageProjectionPort {

  /** 消息被撤回/删除时移除对应热消息。 */
  void deleteByMsgId(String msgId);

  /** 会话被清空时移除该会话下的全部热消息。 */
  void deleteByConversationId(String conversationId);
}
