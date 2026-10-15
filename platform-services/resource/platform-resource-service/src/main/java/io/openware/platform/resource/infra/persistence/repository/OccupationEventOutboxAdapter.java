package io.openware.platform.resource.infra.persistence.repository;

import io.openware.platform.resource.domain.occupation.repository.OccupationEventOutbox;
import io.openware.platform.resource.infra.persistence.mapper.EventOutboxMapper;
import io.openware.platform.resource.infra.persistence.po.ResEventOutboxPo;
import java.time.LocalDateTime;
import org.springframework.stereotype.Repository;

/**
 * {@link OccupationEventOutbox} 的持久化适配器：把待投递事件落成一行 PENDING 记录。
 *
 * <p>投递由 Relay 在事务提交后异步完成，本层不发 MQ。审计字段由本层填充
 * （res_event_outbox 的 created_at/updated_at 为 NOT NULL 无默认值）。
 */
@Repository
public class OccupationEventOutboxAdapter implements OccupationEventOutbox {

  private static final String STATUS_PENDING = "PENDING";
  private static final String AGGREGATE_TYPE = "occupation";

  private final EventOutboxMapper mapper;

  public OccupationEventOutboxAdapter(EventOutboxMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void append(String eventId, String eventType, String aggregateId, String payloadJson) {
    LocalDateTime now = LocalDateTime.now();
    ResEventOutboxPo outbox = new ResEventOutboxPo();
    outbox.setEventId(eventId);
    outbox.setEventType(eventType);
    outbox.setAggregateType(AGGREGATE_TYPE);
    outbox.setAggregateId(aggregateId);
    outbox.setPayloadJson(payloadJson);
    outbox.setStatus(STATUS_PENDING);
    outbox.setRetryCount(0);
    outbox.setCreatedAt(now);
    outbox.setUpdatedAt(now);
    mapper.insert(outbox);
  }
}
