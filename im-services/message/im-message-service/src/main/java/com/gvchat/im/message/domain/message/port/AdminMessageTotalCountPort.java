package com.gvchat.im.message.domain.message.port;

/** 管理端消息总数缓存端口：缓存分页 COUNT(*) 的近似总数，未命中返回 null。 */
public interface AdminMessageTotalCountPort {
  Long get();

  void put(long total);
}
