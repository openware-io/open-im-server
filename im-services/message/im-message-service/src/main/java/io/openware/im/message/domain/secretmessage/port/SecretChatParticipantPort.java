package io.openware.im.message.domain.secretmessage.port;

import java.util.List;

/** 私密会话参与方端口：供消息服务查询私密消息的接收方/参与方（用于离线推送与销毁推送）。 */
public interface SecretChatParticipantPort {
  /**
   * 返回 [senderId] 在会话 [secretChatId] 中的对端（接收方）；会话不存在或调用
   * 失败返回 0（不推送，避免误伤）。
   */
  long findPeerUserId(long secretChatId, long senderId);

  /** 返回会话双方 userId（userA + userB）；会话不存在/调用失败返回空列表。 */
  List<Long> findParticipants(long secretChatId);
}
