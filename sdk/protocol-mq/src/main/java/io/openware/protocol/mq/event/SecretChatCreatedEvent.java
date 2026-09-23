package io.openware.protocol.mq.event;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 私密聊天创建事件：仅含会话与双方参与者元数据，**绝不含密钥/内容**。
 *
 * <p>由会话服务在创建私密聊天后同事务写入 Outbox，im-access-ws 消费后对**对方**
 * 推 WS 信号，促使其同步会话列表并完成 E2EE 握手，从而消除「对方首条消息滞后」。</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecretChatCreatedEvent {
  private String eventId;
  private long secretChatId;
  private long initiatorUserId;
  private long peerUserId;
  private Instant createdAt;
}
