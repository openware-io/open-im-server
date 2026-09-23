package io.openware.im.message.domain.message.port;

/**
 * 功能开关端口：消息服务读取管理端下发的功能开关做服务端强制校验。
 * 读取失败时各实现应默认放行（true），避免配置不可用时阻断消息主链路。
 */
public interface FeatureTogglePort {
  /** 云端单聊开关。 */
  boolean isPrivateChatEnabled();

  /** 云端群组开关。 */
  boolean isGroupChatEnabled();

  default boolean isChannelEnabled() { return true; }

  default boolean isSecretChatEnabled() { return true; }

  default boolean isSecretGroupChatEnabled() { return true; }

  /** 聊天删除/撤回/清空总开关。 */
  boolean isChatDeleteEnabled();
}
