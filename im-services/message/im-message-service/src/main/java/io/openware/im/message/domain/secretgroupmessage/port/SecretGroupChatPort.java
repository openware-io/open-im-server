package io.openware.im.message.domain.secretgroupmessage.port;

import java.util.List;

/** 私密群聊端口：供消息服务查询群成员与销毁策略（走会话服务内部接口）。 */
public interface SecretGroupChatPort {
  /** 返回群全部成员 userId；会话不存在/调用失败返回空列表。 */
  List<Long> findParticipants(long secretGroupId);

  /** 返回群销毁策略（off/30s/5m/1h/1d）；会话不存在/调用失败按 off 处理。 */
  String destroyPolicyOf(long secretGroupId);

  /** 返回群是否「仅群主可发言」；会话不存在/调用失败按 false。 */
  boolean isOwnerOnlyPost(long secretGroupId);

  /** 返回群主 userId；会话不存在/调用失败返回 0。 */
  long ownerOf(long secretGroupId);
}
