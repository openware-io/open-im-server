package io.openware.common.payment.infra.persistence.repository;

import io.openware.common.payment.domain.event.model.PaymentEventOutboxEntry;
import io.openware.common.payment.domain.event.repository.PaymentEventOutbox;
import io.openware.common.payment.infra.persistence.mapper.EventOutboxMapper;
import io.openware.common.payment.infra.persistence.po.PayEventOutboxPo;
import java.time.LocalDateTime;
import org.springframework.stereotype.Repository;

/**
 * {@link PaymentEventOutbox} 的持久化适配器。
 *
 * <p>本层补齐状态与重试计数初值，以及审计字段（{@code created_at}/{@code updated_at} 为 NOT NULL
 * 无默认值）：调用方只表达"这条事件要发出去"。
 *
 * <p>投递不在本层发生——Relay 在事务提交后异步推进状态。
 */
@Repository
public class PaymentEventOutboxAdapter implements PaymentEventOutbox {

  private final EventOutboxMapper mapper;

  public PaymentEventOutboxAdapter(EventOutboxMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public boolean append(PaymentEventOutboxEntry entry) {
    LocalDateTime now = entry.createdAt() == null ? LocalDateTime.now() : entry.createdAt();
    PayEventOutboxPo po = new PayEventOutboxPo();
    po.setTenantId(entry.tenantId());
    po.setEventId(entry.eventId());
    po.setEventType(entry.eventType());
    po.setAggregateType(entry.aggregateType());
    po.setAggregateId(entry.aggregateId());
    po.setPayloadJson(entry.payloadJson());
    po.setStatus(PaymentEventOutboxEntry.STATUS_PENDING);
    po.setRetryCount(PaymentEventOutboxEntry.INITIAL_RETRY_COUNT);
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    return mapper.insert(po) > 0;
  }
}
