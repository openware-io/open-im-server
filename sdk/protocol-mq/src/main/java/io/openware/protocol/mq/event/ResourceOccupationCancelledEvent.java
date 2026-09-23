package io.openware.protocol.mq.event;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 资源占用取消事件（最小化载荷，带租户上下文，不含敏感原文）。 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceOccupationCancelledEvent {
  private String eventId;
  private Long tenantId;
  private Long storeId;
  private Long resourceId;
  private Long occupationId;
  private String sourceType;
  private Long sourceId;
  private Instant startAt;
  private Instant endAt;
  private String status;
  private Instant occurredAt;
}
