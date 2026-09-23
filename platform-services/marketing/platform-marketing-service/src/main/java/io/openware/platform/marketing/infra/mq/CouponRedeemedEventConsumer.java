package io.openware.platform.marketing.infra.mq;

import io.openware.infrastructure.mq.MqConsumerFactory;
import io.openware.infrastructure.mq.json.MqJsonCodec;
import io.openware.platform.marketing.infra.persistence.mapper.MktEventConsumedMapper;
import io.openware.platform.marketing.infra.persistence.po.MktEventConsumedPo;
import io.openware.protocol.mq.event.CouponRedeemedEvent;
import io.openware.protocol.mq.event.MktEventTypes;
import io.openware.protocol.mq.group.MktMqConsumerGroups;
import io.openware.protocol.mq.topic.MktMqTopics;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * coupon.redeemed 事件消费者。以 eventId 为幂等键写入 mkt_event_consumed，
 * 唯一键 uk_mkt_event_consumed_event 兜底去重，重复消费直接跳过。
 */
@Component
@Slf4j
public class CouponRedeemedEventConsumer implements SmartLifecycle {
  private final MqConsumerFactory mqConsumerFactory;
  private final MqJsonCodec mqJsonCodec;
  private final MktEventConsumedMapper consumedMapper;

  private volatile AutoCloseable consumer;
  private volatile boolean running;

  public CouponRedeemedEventConsumer(MqConsumerFactory mqConsumerFactory, MqJsonCodec mqJsonCodec,
      MktEventConsumedMapper consumedMapper) {
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
      consumer = mqConsumerFactory.createOrderedConsumer(MktMqTopics.COUPON_REDEEMED_EVENT,
          MktMqConsumerGroups.PLATFORM_MARKETING_SERVICE_COUPON_REDEEMED,
          body -> consume(mqJsonCodec.fromBytes(body, CouponRedeemedEvent.class)));
      running = true;
      log.info("Started coupon redeemed consumer, topic={}, consumerGroup={}",
          MktMqTopics.COUPON_REDEEMED_EVENT,
          MktMqConsumerGroups.PLATFORM_MARKETING_SERVICE_COUPON_REDEEMED);
    } catch (Exception exception) {
      log.error("Failed to start coupon redeemed consumer.", exception);
      throw new IllegalStateException("Failed to start coupon redeemed consumer.", exception);
    }
  }

  private void consume(CouponRedeemedEvent event) {
    try {
      MktEventConsumedPo po = new MktEventConsumedPo();
      po.setTenantId(event.getTenantId());
      po.setEventId(event.getEventId());
      po.setEventType(MktEventTypes.COUPON_REDEEMED);
      po.setAggregateType("coupon");
      po.setAggregateId(event.getIssuanceId() == null ? null : String.valueOf(event.getIssuanceId()));
      po.setConsumedAt(LocalDateTime.now());
      consumedMapper.insert(po);
      log.info("消费 coupon.redeemed 事件成功, eventId={}, issuanceId={}", event.getEventId(),
          event.getIssuanceId());
    } catch (DuplicateKeyException duplicate) {
      log.info("coupon.redeemed 事件已消费，幂等跳过, eventId={}", event.getEventId());
    }
  }

  @Override
  public void stop() {
    running = false;
    if (consumer != null) {
      try {
        consumer.close();
      } catch (Exception exception) {
        log.warn("Failed to close coupon redeemed consumer cleanly.", exception);
      } finally {
        consumer = null;
      }
    }
    log.info("Stopped coupon redeemed consumer.");
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
