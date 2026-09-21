package com.gvchat.protocol.mq.event;

import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 私密群聊消息存储事件：仅含元数据（群/发送方/接收方列表/时间），**绝不含密文或明文内容**。
 *
 * <p>由消息服务在密文落库后同事务写入 Outbox，im-access-ws 消费后对在线接收方推 WS
 * 信号、对后台接收方推 JPush 到达提示。</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecretGroupMessageStoredEvent {
  private String eventId;
  private long secretGroupId;
  private String msgId;
  private long senderId;
  private List<Long> recipientUserIds;
  private List<Long> atUserIds;
  private Instant createdAt;
}
