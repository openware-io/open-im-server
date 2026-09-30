package io.openware.platform.customer.infra.messaging;

import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.customer.application.CustomerEventOutbox;
import io.openware.platform.customer.application.CustomerFactEvent;
import io.openware.platform.customer.infra.persistence.mapper.CustomerEventOutboxMapper;
import io.openware.platform.customer.infra.persistence.po.CstEventOutboxPo;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 将应用层事实事件写入 Customer Outbox；不在这里发送 MQ。 */
@Component
public class CustomerEventOutboxAppender implements CustomerEventOutbox {
    private final CustomerEventOutboxMapper outboxMapper;

    public CustomerEventOutboxAppender(CustomerEventOutboxMapper outboxMapper) {
        this.outboxMapper = outboxMapper;
    }

    @Override
    public void append(CustomerFactEvent event) {
        Long tenantId = TenantContextHolder.tenantIdOrNull();
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalStateException("租户上下文缺失，拒绝写入 Customer Outbox");
        }
        LocalDateTime now = LocalDateTime.now();
        CstEventOutboxPo row = new CstEventOutboxPo();
        row.setTenantId(tenantId);
        row.setEventId(UUID.randomUUID().toString());
        row.setEventType(event.eventType());
        row.setAggregateType(event.aggregateType());
        row.setAggregateId(event.aggregateId());
        row.setPayloadJson(event.payloadJson());
        row.setStatus("PENDING");
        row.setRetryCount(0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        outboxMapper.insert(row);
    }
}
