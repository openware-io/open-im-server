package io.openware.protocol.mq.event;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 订单创建事件占位（最小化载荷，带租户上下文，不含敏感原文）。 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent {
  private String eventId;
  private Long tenantId;
  private Long storeId;
  private Long orderId;
  private String orderNo;
  private String status;
  private Instant occurredAt;
}
