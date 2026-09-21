package com.gvchat.protocol.mq.event;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserStatusChangedEvent {
  private String eventId;
  private Long userId;
  private String previousStatus;
  private String status;
  private long statusVersion;
  private Long operatorId;
  private String reason;
  private String correlationId;
  private Instant occurredAt;
}
