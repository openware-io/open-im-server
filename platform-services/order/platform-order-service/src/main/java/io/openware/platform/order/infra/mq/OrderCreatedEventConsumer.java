package io.openware.platform.order.infra.mq;

import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.json.MqJsonCodec;
import io.openware.platform.order.infra.persistence.mapper.OrdEventConsumedMapper;
import io.openware.platform.order.infra.persistence.po.OrdEventConsumedPo;
import io.openware.protocol.mq.event.OrderCreatedEvent;
import io.openware.protocol.mq.event.OrderEventTypes;
import io.openware.protocol.mq.group.OrderMqConsumerGroups;
import io.openware.protocol.mq.topic.OrderMqTopics;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * order.created 事件消费者。以 eventId 为幂等键写入 ord_event_consumed，
 * 唯一键 uk_ord_event_consumed_event 兜底去重，重复消费直接跳过。
 */
@Component
@Slf4j
public class OrderCreatedEventConsumer implements SmartLifecycle {
  private final MqConsumerFactory mqConsumerFactory;
  private final MqJsonCodec mqJsonCodec;
  private final OrdEventConsumedMapper consumedMapper;

  private volatile AutoCloseable consumer;
  private volatile boolean running;

  public OrderCreatedEventConsumer(MqConsumerFactory mqConsumerFactory, MqJsonCodec mqJsonCodec,
      OrdEventConsumedMapper consumedMapper) {
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
      consumer = mqConsumerFactory.createOrderedConsumer(OrderMqTopics.ORDER_CREATED_EVENT,
          OrderMqConsumerGroups.PLATFORM_ORDER_SERVICE_ORDER_CREATED,
          body -> consume(mqJsonCodec.fromBytes(body, OrderCreatedEvent.class)));
      running = true;
      log.info("Started order created consumer, topic={}, consumerGroup={}",
          OrderMqTopics.ORDER_CREATED_EVENT,
          OrderMqConsumerGroups.PLATFORM_ORDER_SERVICE_ORDER_CREATED);
    } catch (Exception exception) {
      log.error("Failed to start order created consumer.", exception);
      throw new IllegalStateException("Failed to start order created consumer.", exception);
    }
  }

  private void consume(OrderCreatedEvent event) {
    try {
      OrdEventConsumedPo po = new OrdEventConsumedPo();
      po.setTenantId(event.getTenantId());
      po.setEventId(event.getEventId());
      po.setEventType(OrderEventTypes.ORDER_CREATED);
      po.setAggregateType("order");
      po.setAggregateId(event.getOrderId() == null ? null : String.valueOf(event.getOrderId()));
      po.setConsumedAt(LocalDateTime.now());
      consumedMapper.insert(po);
      log.info("消费 order.created 事件成功, eventId={}, orderId={}", event.getEventId(),
          event.getOrderId());
    } catch (DuplicateKeyException duplicate) {
      log.info("order.created 事件已消费，幂等跳过, eventId={}", event.getEventId());
    }
  }

  @Override
  public void stop() {
    running = false;
    if (consumer != null) {
      try {
        consumer.close();
      } catch (Exception exception) {
        log.warn("Failed to close order created consumer cleanly.", exception);
      } finally {
        consumer = null;
      }
    }
    log.info("Stopped order created consumer.");
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
