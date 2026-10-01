package io.openware.platform.customer.infra.messaging;

import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.json.MqJsonCodec;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.customer.application.PointApplicationService;
import io.openware.platform.customer.infra.persistence.mapper.CustomerEventConsumedMapper;
import io.openware.platform.customer.infra.persistence.po.CstEventConsumedPo;
import io.openware.protocol.mq.event.CollectConfirmedEvent;
import io.openware.protocol.mq.event.PayEventTypes;
import io.openware.protocol.mq.group.CustomerMqConsumerGroups;
import io.openware.protocol.mq.topic.PayMqTopics;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/** 收款确认后的积分获得消费者：积分成功后才登记 consumed，失败由 MQ 重投。 */
@Component
@ConditionalOnBean(MqConsumerFactory.class)
@Slf4j
public class CollectConfirmedEventConsumer implements SmartLifecycle {
    private final MqConsumerFactory mqConsumerFactory;
    private final MqJsonCodec mqJsonCodec;
    private final PointApplicationService pointService;
    private final CustomerEventConsumedMapper consumedMapper;
    private volatile AutoCloseable consumer;
    private volatile boolean running;

    public CollectConfirmedEventConsumer(MqConsumerFactory mqConsumerFactory, MqJsonCodec mqJsonCodec,
                                         PointApplicationService pointService,
                                         CustomerEventConsumedMapper consumedMapper) {
        this.mqConsumerFactory = mqConsumerFactory;
        this.mqJsonCodec = mqJsonCodec;
        this.pointService = pointService;
        this.consumedMapper = consumedMapper;
    }

    @Override
    public void start() {
        if (running) return;
        try {
            consumer = mqConsumerFactory.createOrderedConsumer(PayMqTopics.COLLECT_CONFIRMED_EVENT,
                    CustomerMqConsumerGroups.PLATFORM_CUSTOMER_SERVICE_COLLECT_CONFIRMED,
                    body -> consume(mqJsonCodec.fromBytes(body, CollectConfirmedEvent.class)));
            running = true;
        } catch (Exception exception) {
            throw new IllegalStateException("无法启动收款确认积分消费者", exception);
        }
    }

    private void consume(CollectConfirmedEvent event) {
        if (event == null || event.getEventId() == null || event.getTenantId() == null
                || event.getCustomerId() == null || event.getStoreId() == null) {
            throw new IllegalArgumentException("收款确认事件缺少积分归因字段");
        }
        TenantContextHolder.set(new TenantContext(event.getTenantId(), null, event.getStoreId(), 0L, 0));
        try {
            pointService.earnFromConfirmedCollection(event.getStoreId(), event.getCustomerId(),
                    event.getEligibleAmount(), event.getOrderId(), event.getEventId());
            CstEventConsumedPo consumed = new CstEventConsumedPo();
            consumed.setTenantId(event.getTenantId());
            consumed.setEventId(event.getEventId());
            consumed.setEventType(PayEventTypes.COLLECT_CONFIRMED);
            consumed.setAggregateType("collect");
            consumed.setAggregateId(event.getCollectNo());
            consumed.setConsumedAt(LocalDateTime.now());
            try {
                consumedMapper.insert(consumed);
            } catch (DuplicateKeyException duplicate) {
                log.info("收款确认积分事件已消费，幂等跳过, eventId={}", event.getEventId());
            }
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Override public void stop() {
        running = false;
        if (consumer != null) { try { consumer.close(); } catch (Exception e) { log.warn("关闭积分事件消费者失败", e); } finally { consumer = null; } }
    }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    @PreDestroy void onDestroy() { stop(); }
    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 200; }
}
