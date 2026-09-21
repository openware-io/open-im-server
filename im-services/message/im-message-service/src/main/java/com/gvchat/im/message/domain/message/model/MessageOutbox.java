package com.gvchat.im.message.domain.message.model;

import java.time.LocalDateTime;

public class MessageOutbox {
  private Long id;
  private final String eventId;
  private final String aggregateType;
  private final String aggregateId;
  private final String topic;
  private final String shardingKey;
  private final String payloadJson;
  private boolean published;
  private final LocalDateTime createdAt;
  private LocalDateTime publishedAt;

  private MessageOutbox(Long id, String eventId, String aggregateType, String aggregateId, String topic,
      String shardingKey, String payloadJson, boolean published, LocalDateTime createdAt,
      LocalDateTime publishedAt) {
    this.id = id;
    this.eventId = eventId;
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.topic = topic;
    this.shardingKey = shardingKey;
    this.payloadJson = payloadJson;
    this.published = published;
    this.createdAt = createdAt;
    this.publishedAt = publishedAt;
  }

  public static MessageOutbox pending(String eventId, String aggregateType, String aggregateId, String topic,
      String shardingKey, String payloadJson, LocalDateTime createdAt) {
    return new MessageOutbox(null, eventId, aggregateType, aggregateId, topic, shardingKey, payloadJson,
        false, createdAt, null);
  }

  public static MessageOutbox restore(Long id, String eventId, String aggregateType, String aggregateId,
      String topic, String shardingKey, String payloadJson, boolean published, LocalDateTime createdAt,
      LocalDateTime publishedAt) {
    return new MessageOutbox(id, eventId, aggregateType, aggregateId, topic, shardingKey, payloadJson,
        published, createdAt, publishedAt);
  }

  public void markPublished(LocalDateTime occurredAt) { published = true; publishedAt = occurredAt; }
  public void assignId(Long id) { this.id = id; }
  public Long getId() { return id; }
  public String getEventId() { return eventId; }
  public String getAggregateType() { return aggregateType; }
  public String getAggregateId() { return aggregateId; }
  public String getTopic() { return topic; }
  public String getShardingKey() { return shardingKey; }
  public String getPayloadJson() { return payloadJson; }
  public boolean isPublished() { return published; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public LocalDateTime getPublishedAt() { return publishedAt; }
}
