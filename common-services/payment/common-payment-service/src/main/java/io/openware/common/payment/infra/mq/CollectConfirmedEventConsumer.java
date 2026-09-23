package io.openware.common.payment.infra.mq;

import io.openware.common.payment.infra.persistence.mapper.PayEventConsumedMapper;
import io.openware.common.payment.infra.persistence.po.PayEventConsumedPo;
import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.json.MqJsonCodec;
import io.openware.protocol.mq.event.CollectConfirmedEvent;
import io.openware.protocol.mq.event.PayEventTypes;
import io.openware.protocol.mq.group.PayMqConsumerGroups;
import io.openware.protocol.mq.topic.PayMqTopics;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * payment.collect.confirmed 事件消费者。以 eventId 为幂等键写入 pay_event_consumed，
 * 唯一键 uk_pay_event_consumed_event 兜底去重，重复消费直接跳过。
 */
@Component
@Slf4j
public class CollectConfirmedEventConsumer implements SmartLifecycle {
  private final MqConsumerFactory mqConsumerFactory;
  private final MqJsonCodec mqJsonCodec;
  private final PayEventConsumedMapper consumedMapper;

  private volatile AutoCloseable consumer;
  private volatile boolean running;

  public CollectConfirmedEventConsumer(MqConsumerFactory mqConsumerFactory, MqJsonCodec mqJsonCodec,
      PayEventConsumedMapper consumedMapper) {
    this.mqConsumerFactory = mqConsumerFactory;
    this.mqJsonCodec = mqJsonCodec;
    this.consumedMapper = consumedMapper;
  }

  @Override
  public void start() {
    if (running) {
      return;
    }
    try {
      consumer = mqConsumerFactory.createOrderedConsumer(PayMqTopics.COLLECT_CONFIRMED_EVENT,
          PayMqConsumerGroups.COMMON_PAYMENT_SERVICE_COLLECT_CONFIRMED,
          body -> consume(mqJsonCodec.fromBytes(body, CollectConfirmedEvent.class)));
      running = true;
      log.info("Started collect confirmed consumer, topic={}, consumerGroup={}",
          PayMqTopics.COLLECT_CONFIRMED_EVENT,
          PayMqConsumerGroups.COMMON_PAYMENT_SERVICE_COLLECT_CONFIRMED);
    } catch (Exception exception) {
      log.error("Failed to start collect confirmed consumer.", exception);
      throw new IllegalStateException("Failed to start collect confirmed consumer.", exception);
    }
  }

  private void consume(CollectConfirmedEvent event) {
    try {
      PayEventConsumedPo po = new PayEventConsumedPo();
      po.setTenantId(event.getTenantId());
      po.setEventId(event.getEventId());
      po.setEventType(PayEventTypes.COLLECT_CONFIRMED);
      po.setAggregateType("collect");
      po.setAggregateId(event.getCollectNo());
      po.setConsumedAt(LocalDateTime.now());
      consumedMapper.insert(po);
      log.info("消费 payment.collect.confirmed 事件成功, eventId={}, collectNo={}", event.getEventId(),
          event.getCollectNo());
    } catch (DuplicateKeyException duplicate) {
      log.info("payment.collect.confirmed 事件已消费，幂等跳过, eventId={}", event.getEventId());
    }
  }

  @Override
  public void stop() {
    running = false;
    if (consumer != null) {
      try {
        consumer.close();
      } catch (Exception exception) {
        log.warn("Failed to close collect confirmed consumer cleanly.", exception);
      } finally {
        consumer = null;
      }
    }
    log.info("Stopped collect confirmed consumer.");
  }

  @Override
  public void stop(Runnable callback) {
    stop();
    callback.run();
  }

  @PreDestroy
  void onDestroy() {
    stop();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 200;
  }
}
