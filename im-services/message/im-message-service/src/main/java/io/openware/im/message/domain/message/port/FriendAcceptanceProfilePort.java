package io.openware.im.message.domain.message.port;

/** 好友通过自动消息所需的发送方资料端口。实现由用户服务内部接口提供。 */
public interface FriendAcceptanceProfilePort {
  SenderProfile find(long userId);

  record SenderProfile(long userId, String username) {
  }
}
