package io.openware.im.message.domain.message.model;

import java.time.LocalDateTime;

/**
 * 事务性发件箱记录：事件负载的可靠投递载体，由 Relay 在事务提交后异步发布。
 *
 * <p><b>为什么不带 {@code createdAt}</b>（standards/10 L299）：入队时刻是记账信息，由适配器在写库时确定。
 * 核实过：全部 7 处调用方传入的都是「当前时刻」，Relay 按 {@code id} 升序取待发布批次（不按入队时刻排序），
 * 也没有任何读取方使用该值——因此它既无读者也不参与判定，可直接下沉。
 *
 * <p><b>{@code publishedAt} 保留</b>：它是「何时投递成功」的业务事实，由 {@code markPublished} 写入，
 * 与记账列不同。
 */
public class MessageOutbox {
  private Long id;
  private final String eventId;
  private final String aggregateType;
  private final String aggregateId;
  private final String topic;
  private final String shardingKey;
  private final String payloadJson;
  private boolean published;
  private LocalDateTime publishedAt;

  private MessageOutbox(Long id, String eventId, String aggregateType, String aggregateId, String topic,
      String shardingKey, String payloadJson, boolean published, LocalDateTime publishedAt) {
    this.id = id;
    this.eventId = eventId;
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.topic = topic;
    this.shardingKey = shardingKey;
    this.payloadJson = payloadJson;
    this.published = published;
    this.publishedAt = publishedAt;
  }

  public static MessageOutbox pending(String eventId, String aggregateType, String aggregateId, String topic,
      String shardingKey, String payloadJson) {
    return new MessageOutbox(null, eventId, aggregateType, aggregateId, topic, shardingKey, payloadJson,
        false, null);
  }

  /** 由持久化对象重建领域对象（仅适配器使用）。 */
  public static MessageOutbox restore(Long id, String eventId, String aggregateType, String aggregateId,
      String topic, String shardingKey, String payloadJson, boolean published,
      LocalDateTime publishedAt) {
    return new MessageOutbox(id, eventId, aggregateType, aggregateId, topic, shardingKey, payloadJson,
        published, publishedAt);
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
  public LocalDateTime getPublishedAt() { return publishedAt; }
}
