package io.openware.protocol.mq.event;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 组合收款确认事件占位（最小化载荷，带租户上下文，不含敏感原文）。 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollectConfirmedEvent {
  private String eventId;
  private Long tenantId;
  private Long orderId;
  private String collectNo;
  private long collectedAmount;
  private Instant occurredAt;
}
