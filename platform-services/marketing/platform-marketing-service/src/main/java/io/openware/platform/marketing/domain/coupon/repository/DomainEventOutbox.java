package io.openware.platform.marketing.domain.coupon.repository;

/**
 * 领域事件出站记录端口（Outbox 追加）。
 *
 * <p>投递本身属基础设施（standards/10“领域事件与可靠发布”：RocketMQ 投递只能由 Relay 在事务提交后异步执行），
 * 领域与应用层只负责**在同一事务内**追加出站记录，因此这里只暴露"追加一条待投递事件"。
 *
 * <p>用语义化方法名而不是暴露 payload/状态的写入细节：调用方表达的是"发生了这样一个业务事实"，
 * 而不是"往某张表写一行"。
 */
public interface DomainEventOutbox {

  /**
   * 追加一条待投递的领域事件（与业务写入同事务）。
   *
   * @param tenantId      租户标识；平台级事件可为空（建表该列可空）
   * @param aggregateType 聚合类型标识
   * @param aggregateId   聚合标识
   * @param eventType     事件类型（取自 MQ 台账，见 CouponMqRegistry）
   * @param eventId       事件标识；消费者以它幂等
   * @param payloadJson   事件负载 JSON
   */
  void append(Long tenantId, String aggregateType, String aggregateId, String eventType,
              String eventId, String payloadJson);
}
