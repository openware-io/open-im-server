package com.gvchat.im.message.domain.secretmessage.port;

/** 私密聊天会话策略端口：消息服务通过内部接口读取会话销毁策略，驱动定时销毁引擎。 */
public interface SecretChatPolicyPort {
  /** 返回会话销毁策略（off / 30s / 5m / 1h / 1d）；会话不存在或调用失败时按 off 处理。 */
  String destroyPolicyOf(long secretChatId);
}
