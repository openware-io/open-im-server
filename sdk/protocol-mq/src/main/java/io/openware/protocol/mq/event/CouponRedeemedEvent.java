package io.openware.protocol.mq.event;

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
public class CouponRedeemedEvent {
  private String eventId;
  private Long tenantId;
  private Long issuanceId;
  private Long couponId;
  private Long customerId;
  private Long orderId;
  private Long amount;
  private Long discountAmount;
  private Instant occurredAt;
}
