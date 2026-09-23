package io.openware.im.conversation.domain.account.port;

/** 用户会话数据清理端口：硬删指定用户在会话服务拥有的全部会话数据（群成员/私密会话/频道订阅等）。 */
public interface UserConversationDataPurger {
  void purge(long userId);
}
