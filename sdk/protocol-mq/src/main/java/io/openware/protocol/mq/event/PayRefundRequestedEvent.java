package io.openware.protocol.mq.event;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 退款申请事件（最小化载荷，带租户上下文，不含敏感原文）。 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayRefundRequestedEvent {
  private String eventId;
  private Long tenantId;
  private Long orderId;
  private String refundNo;
  private long refundAmount;
  private Instant occurredAt;
}