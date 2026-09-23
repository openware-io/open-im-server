package io.openware.im.message.domain.message.port;

/** 用户消息数据清理端口：硬删指定用户在消息服务拥有的全部消息数据（含密文）。 */
public interface UserMessageDataPurger {
  void purge(long userId);
}
