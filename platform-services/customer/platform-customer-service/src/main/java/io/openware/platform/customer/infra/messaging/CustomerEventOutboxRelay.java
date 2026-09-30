package io.openware.platform.customer.infra.messaging;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.infrastructure.mq.MqProducer;
import io.openware.platform.customer.infra.persistence.mapper.CustomerEventOutboxMapper;
import io.openware.platform.customer.infra.persistence.po.CstEventOutboxPo;
import io.openware.protocol.mq.topic.CustomerMqTopics;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Customer Outbox Relay：复用仓内 PENDING/FAILED + 指数退避投递规范。 */
@Component
@ConditionalOnBean(MqProducer.class)
public class CustomerEventOutboxRelay {
    private final CustomerEventOutboxMapper outboxMapper;
    private final MqProducer mqProducer;
    private final int batchSize;
    private final int maxRetryCount;

    public CustomerEventOutboxRelay(CustomerEventOutboxMapper outboxMapper, MqProducer mqProducer,
                                    @Value("${platform.customer.outbox.batch-size:100}") int batchSize,
                                    @Value("${platform.customer.outbox.max-retry-count:10}") int maxRetryCount) {
        this.outboxMapper = outboxMapper;
        this.mqProducer = mqProducer;
        this.batchSize = batchSize;
        this.maxRetryCount = maxRetryCount;
    }

    @Scheduled(fixedDelayString = "${platform.customer.outbox.relay-delay-ms:1000}")
    public void relayPendingEvents() {
        LocalDateTime now = LocalDateTime.now();
        List<CstEventOutboxPo> rows = outboxMapper.selectList(new LambdaQueryWrapper<CstEventOutboxPo>()
                .in(CstEventOutboxPo::getStatus, "PENDING", "FAILED")
                .and(w -> w.isNull(CstEventOutboxPo::getNextRetryAt).or().le(CstEventOutboxPo::getNextRetryAt, now))
                .orderByAsc(CstEventOutboxPo::getId).last("LIMIT " + batchSize));
        for (CstEventOutboxPo row : rows) {
            try {
                mqProducer.sendOrdered(CustomerMqTopics.FACT_EVENT,
                        String.valueOf(row.getTenantId()), row.getEventId(),
                        row.getPayloadJson().getBytes(StandardCharsets.UTF_8));
                row.setStatus("PUBLISHED");
                row.setPublishedAt(now);
                row.setUpdatedAt(now);
                outboxMapper.updateById(row);
            } catch (Exception failure) {
                int retries = (row.getRetryCount() == null ? 0 : row.getRetryCount()) + 1;
                row.setRetryCount(retries);
                row.setStatus(retries >= maxRetryCount ? "DEAD" : "FAILED");
                row.setNextRetryAt(retries >= maxRetryCount
                        ? null : now.plusSeconds(Math.min(3600L, 1L << Math.min(retries, 12))));
                row.setUpdatedAt(now);
                outboxMapper.updateById(row);
            }
        }
    }
}
