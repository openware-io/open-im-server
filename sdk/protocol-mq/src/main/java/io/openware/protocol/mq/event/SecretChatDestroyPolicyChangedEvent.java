package io.openware.protocol.mq.event;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 私密聊天定时销毁策略变更事件：设置/修改销毁策略后触发。
 *
 * <p>消息服务消费后对历史消息做「回补销毁」：为尚未计时（destroyAt 为空）的历史消息按
 * 发送时间 + 新策略时长计算销毁时刻，已到期的立即销毁、未到期的补上计时。</p>
 *
 * <p>仅含会话元数据与新策略，**绝不含密钥/内容**。</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecretChatDestroyPolicyChangedEvent {
  private String eventId;
  private long secretChatId;
  private String policy;
  private Instant changedAt;
}
