package io.openware.im.conversation.domain.group.port;

/** 功能开关端口：会话服务读取管理端下发的群聊开关做服务端强制校验；读取失败默认放行。 */
public interface FeatureTogglePort {
  /** 云端群组开关（关闭时禁止创建群）。 */
  boolean isGroupChatEnabled();
}
