package com.gvchat.common.payment.infra.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gvchat.common.payment.infra.persistence.mapper.EventOutboxMapper;
import com.gvchat.common.payment.infra.persistence.po.PayEventOutboxPo;
import com.gvchat.infrastructure.mq.MqProducer;
import com.gvchat.protocol.mq.event.PayEventTypes;
import com.gvchat.protocol.mq.topic.PayMqTopics;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 支付事件 Outbox Relay。定时扫描 pay_event_outbox 中 PENDING/FAILED 且到期的记录，
 * 按 event_type 投递 {@link PayMqTopics#COLLECT_CONFIRMED_EVENT} / {@link PayMqTopics#REFUND_REQUESTED_EVENT}；
 * 成功置 PUBLISHED，失败置 FAILED 并按指数退避安排 next_retry_at，超过最大重试次数进入死信并告警。
 * 口径依据：SAAS_PLATFORM_06_TECHNICAL.md §8；顺序键 tenantId，幂等键 eventId。
 */
@Component
@Slf4j
public class EventOutboxRelay {
  private static final String STATUS_PENDING = "PENDING";
  private static final String STATUS_PUBLISHED = "PUBLISHED";
  private static final String STATUS_FAILED = "FAILED";

  private final EventOutboxMapper outboxMapper;
  private final MqProducer mqProducer;
  private final int batchSize;
  private final int maxRetryCount;
  private final long baseRetryDelaySeconds;
  private final long maxBackoffSeconds;

  public EventOutboxRelay(EventOutboxMapper outboxMapper, MqProducer mqProducer,
      @Value("${platform.payment.outbox.batch-size:100}") int batchSize,
      @Value("${platform.payment.outbox.max-retry-count:10}") int maxRetryCount,
      @Value("${platform.payment.outbox.base-retry-delay-seconds:1}") long baseRetryDelaySeconds,
      @Value("${platform.payment.outbox.max-backoff-seconds:3600}") long maxBackoffSeconds) {
    this.outboxMapper = outboxMapper;
    this.mqProducer = mqProducer;
    this.batchSize = batchSize;
    this.maxRetryCount = maxRetryCount;
    this.baseRetryDelaySeconds = baseRetryDelaySeconds;
    this.maxBackoffSeconds = maxBackoffSeconds;
  }

  @Scheduled(fixedDelayString = "${platform.payment.outbox.relay-delay-ms:1000}")
  public void relayPendingEvents() {
    LocalDateTime now = LocalDateTime.now();
    List<PayEventOutboxPo> rows = outboxMapper.selectList(new LambdaQueryWrapper<PayEventOutboxPo>()
        .in(PayEventOutboxPo::getStatus, STATUS_PENDING, STATUS_FAILED)
        .and(w -> w.isNull(PayEventOutboxPo::getNextRetryAt).or().le(PayEventOutboxPo::getNextRetryAt, now))
        .orderByAsc(PayEventOutboxPo::getId)
        .last("LIMIT " + batchSize));
    if (!rows.isEmpty()) {
      log.info("开始转发待发布的支付 Outbox 事件, pendingCount={}", rows.size());
    }
    for (PayEventOutboxPo row : rows) {
      relay(row, now);
    }
  }

  private void relay(PayEventOutboxPo row, LocalDateTime now) {
    String topic = resolveTopic(row.getEventType());
    String shardingKey = shardingKey(row);
    try {
      mqProducer.sendOrdered(topic, shardingKey, row.getEventId(),
          row.getPayloadJson().getBytes(StandardCharsets.UTF_8));
      row.setStatus(STATUS_PUBLISHED);
      row.setPublishedAt(now);
      row.setUpdatedAt(now);
      outboxMapper.updateById(row);
      log.info("支付 Outbox 事件转发成功, eventId={}, topic={}, shardingKey={}", row.getEventId(), topic, shardingKey);
    } catch (Exception exception) {
      markFailed(row, now, exception);
    }
  }

  private void markFailed(PayEventOutboxPo row, LocalDateTime now, Exception exception) {
    int retryCount = (row.getRetryCount() == null ? 0 : row.getRetryCount()) + 1;
    row.setRetryCount(retryCount);
    row.setUpdatedAt(now);
    if (retryCount >= maxRetryCount) {
      row.setStatus(STATUS_FAILED);
      row.setNextRetryAt(null);
      outboxMapper.updateById(row);
      log.error("支付 Outbox 事件超过最大重试次数，进入死信并告警, eventId={}, retryCount={}",
          row.getEventId(), retryCount, exception);
    } else {
      long delaySeconds = backoffSeconds(retryCount);
      row.setStatus(STATUS_FAILED);
      row.setNextRetryAt(now.plusSeconds(delaySeconds));
      outboxMapper.updateById(row);
      log.warn("支付 Outbox 事件转发失败，将按指数退避重试, eventId={}, retryCount={}, nextRetryAt={}",
          row.getEventId(), retryCount, row.getNextRetryAt(), exception);
    }
  }

  private long backoffSeconds(int retryCount) {
    long delay = baseRetryDelaySeconds;
    for (int i = 1; i < retryCount; i++) {
      delay = Math.min(delay * 2, maxBackoffSeconds);
    }
    return Math.min(delay, maxBackoffSeconds);
  }

  private String resolveTopic(String eventType) {
    if (PayEventTypes.REFUND_REQUESTED.equals(eventType)) {
      return PayMqTopics.REFUND_REQUESTED_EVENT;
    }
    return PayMqTopics.COLLECT_CONFIRMED_EVENT;
  }

  private String shardingKey(PayEventOutboxPo row) {
    return row.getTenantId() != null ? String.valueOf(row.getTenantId()) : "0";
  }
}
