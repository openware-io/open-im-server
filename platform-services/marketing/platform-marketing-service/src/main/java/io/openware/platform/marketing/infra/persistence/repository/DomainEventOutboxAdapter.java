package io.openware.platform.marketing.infra.persistence.repository;

import io.openware.platform.marketing.domain.coupon.repository.DomainEventOutbox;
import io.openware.platform.marketing.infra.persistence.mapper.EventOutboxMapper;
import io.openware.platform.marketing.infra.persistence.po.MktEventOutboxPo;
import java.time.LocalDateTime;
import org.springframework.stereotype.Repository;

/**
 * {@link DomainEventOutbox} 的持久化适配器：只负责把待投递事件落成一行 PENDING 记录。
 *
 * <p>投递由 Relay 在事务提交后异步完成（standards/10“领域事件与可靠发布”），本层不发 MQ，
 * 也不做"保存后尽力发送"。
 *
 * <p>{@code tenantId} 由调用方给出（建表该列可空，平台级事件为空）；审计字段由本层填充
 * （created_at/updated_at 为 NOT NULL 无默认值）。
 */
@Repository
public class DomainEventOutboxAdapter implements DomainEventOutbox {

  /** 待投递状态。 */
  private static final String STATUS_PENDING = "PENDING";

  private final EventOutboxMapper mapper;

  public DomainEventOutboxAdapter(EventOutboxMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void append(Long tenantId, String aggregateType, String aggregateId, String eventType,
                     String eventId, String payloadJson) {
    LocalDateTime now = LocalDateTime.now();
    MktEventOutboxPo po = new MktEventOutboxPo();
    po.setTenantId(tenantId);
    po.setEventId(eventId);
    po.setEventType(eventType);
    po.setAggregateType(aggregateType);
    po.setAggregateId(aggregateId);
    po.setPayloadJson(payloadJson);
    po.setStatus(STATUS_PENDING);
    po.setRetryCount(0);
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    mapper.insert(po);
  }
}
