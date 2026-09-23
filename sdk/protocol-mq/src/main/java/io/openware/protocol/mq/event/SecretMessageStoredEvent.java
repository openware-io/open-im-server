package io.openware.protocol.mq.event;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 私密消息存储事件：仅含元数据（会话/发送方/接收方/时间），**绝不含密文或明文内容**。
 *
 * <p>由消息服务在密文落库后同事务写入 Outbox，im-access-ws 消费后对离线接收方
 * 触发推送通知（"你有一条加密消息"），在线接收方由客户端轮询兜底。</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecretMessageStoredEvent {
  private String eventId;
  private long secretChatId;
  private String msgId;
  private long senderId;
  private long recipientUserId;
  private Instant createdAt;
}
