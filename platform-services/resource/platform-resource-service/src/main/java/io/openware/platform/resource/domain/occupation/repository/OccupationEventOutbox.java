package io.openware.platform.resource.domain.occupation.repository;

/**
 * 领域事件出站记录端口（Outbox 追加）。
 *
 * <p>投递属基础设施（standards/10“领域事件与可靠发布”：RocketMQ 投递只能由 Relay 在事务提交后异步执行）；
 * 领域与应用层只负责**在同一事务内**追加一条待投递记录。
 */
public interface OccupationEventOutbox {

  /**
   * 追加一条待投递事件（与业务写入同事务）。
   *
   * @param eventId       事件标识；消费者以它幂等
   * @param eventType     事件类型（取自 MQ 台账）
   * @param aggregateId   聚合标识
   * @param payloadJson   事件负载 JSON
   */
  void append(String eventId, String eventType, String aggregateId, String payloadJson);
}
