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
  private Long storeId;
  private Long customerId;
  private Long orderId;
  private String collectNo;
  /** 可获得积分的收款金额（排除积分/储值抵扣分腿，最小货币单位）。 */
  private long eligibleAmount;
  private Instant occurredAt;
}
