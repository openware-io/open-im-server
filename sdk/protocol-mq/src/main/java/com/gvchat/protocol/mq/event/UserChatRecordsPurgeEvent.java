package com.gvchat.protocol.mq.event;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 用户聊天记录清理事件：账号自毁策略到期后，用户服务保留账号、仅触发消息/会话等服务清理该用户的聊天记录
 * （消息、会话、私密会话、群成员关系、频道订阅等）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserChatRecordsPurgeEvent {
  private String eventId;
  private Long userId;
  private Instant occurredAt;
}
