package com.gvchat.im.message.infra.messaging.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.infrastructure.mq.MqProducer;
import com.gvchat.im.message.domain.message.event.MessageEditedEvent;
import com.gvchat.im.message.domain.message.model.MessageOutbox;
import com.gvchat.im.message.domain.message.repository.MessageOutboxRepository;
import com.gvchat.im.message.infra.projection.MessageProjectionService;
import com.gvchat.protocol.mq.event.MessageStoredEvent;
import com.gvchat.protocol.mq.topic.ImMqTopics;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
public class MessageOutboxRelay {
  private final MessageOutboxRepository messageOutboxRepository;
  private final MessageProjectionService messageProjectionService;
  private final MqProducer mqProducer;
  private final ObjectMapper objectMapper;
  private final int batchSize;

  public MessageOutboxRelay(MessageOutboxRepository messageOutboxRepository,
      MessageProjectionService messageProjectionService, MqProducer mqProducer, ObjectMapper objectMapper,
      @Value("${im.message.outbox.batch-size:100}") int batchSize) {
    this.messageOutboxRepository = messageOutboxRepository;
    this.messageProjectionService = messageProjectionService;
    this.mqProducer = mqProducer;
    this.objectMapper = objectMapper;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${im.message.outbox.relay-delay-ms:1000}")
  @Transactional
  public void relayPendingEvents() {
    List<MessageOutbox> pending = messageOutboxRepository.findPending(batchSize);
    if (!pending.isEmpty()) {
      log.info("开始转发待发布的 Outbox 事件, batchSize={}, pendingCount={}", batchSize, pending.size());
    }
    for (MessageOutbox row : pending) {
      relay(row);
    }
  }

  private void relay(MessageOutbox row) {
    try {
      if (ImMqTopics.MESSAGE_STORED_EVENT.equals(row.getTopic())) {
        MessageStoredEvent event = objectMapper.readValue(row.getPayloadJson(), MessageStoredEvent.class);
        messageProjectionService.project(event);
      }
      if (MessageEditedEvent.TOPIC.equals(row.getTopic())) {
        MessageEditedEvent event = objectMapper.readValue(row.getPayloadJson(), MessageEditedEvent.class);
        messageProjectionService.projectEdit(event);
      }
      mqProducer.sendOrdered(row.getTopic(), row.getShardingKey(), row.getAggregateId(),
          row.getPayloadJson().getBytes(StandardCharsets.UTF_8));
      row.markPublished(LocalDateTime.now(java.time.Clock.systemUTC()));
      messageOutboxRepository.save(row);
      log.info("Outbox 事件转发成功, eventId={}, topic={}, shardingKey={}, aggregateId={}", row.getEventId(),
          row.getTopic(), row.getShardingKey(), row.getAggregateId());
    } catch (Exception ex) {
      log.error("Outbox 事件转发失败, eventId={}, topic={}, shardingKey={}", row.getEventId(), row.getTopic(),
          row.getShardingKey(), ex);
      throw new IllegalStateException("Failed to relay outbox event " + row.getEventId(), ex);
    }
  }
}
