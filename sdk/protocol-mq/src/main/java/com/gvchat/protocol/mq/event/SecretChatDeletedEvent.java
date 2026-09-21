package com.gvchat.protocol.mq.event;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 私密聊天终止事件：任意一方删除私密聊天即触发（Telegram 语义：双向删除 + 终止会话）。
 *
 * <p>由会话服务在删除私密聊天后同事务写入 Outbox；消息服务消费后硬删除该会话全部密文，
 * im-access-ws 消费后对**对方**推 WS 信号，使其同步移除会话并清空本地消息。</p>
 *
 * <p>仅含会话与参与者元数据，**绝不含密钥/内容**。</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecretChatDeletedEvent {
  private String eventId;
  private long secretChatId;
  private long initiatorUserId;
  private long peerUserId;
  private Instant deletedAt;
}
