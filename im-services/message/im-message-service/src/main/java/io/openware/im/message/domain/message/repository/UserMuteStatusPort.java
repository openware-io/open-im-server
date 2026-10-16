package io.openware.im.message.domain.message.repository;

/** 用户全局禁言状态端口：用于消息发送链路拦截被全局禁言的用户。 */
public interface UserMuteStatusPort {
  boolean isMuted(long userId);
}
