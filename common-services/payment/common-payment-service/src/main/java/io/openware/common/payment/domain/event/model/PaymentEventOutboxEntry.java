package io.openware.common.payment.domain.event.model;

import java.time.LocalDateTime;

/**
 * 待投递事件（Outbox）条目。
 *
 * <p>用记录而不是八个位置参数：{@code append(tenantId, eventId, eventType, aggregateType, aggregateId,
 * payloadJson, status, retryCount)} 这种签名在调用点无法自解释，两个相邻的字符串参数写反了
 * 编译器也不会拦——而写反的后果是事件永远投不出去或投到错误聚合上。
 *
 * <p>{@code retryCount} 由领域侧固定为 0（新事件尚未重试）；投递与重试计数由 Relay 负责，
 * 不在本模型的职责内。
 *
 * @param tenantId      租户
 * @param eventId       事件标识（消费者据此幂等）
 * @param eventType     事件类型（取自 MQ 台账）
 * @param aggregateType 聚合类型
 * @param aggregateId   聚合标识
 * @param payloadJson   事件负载
 * @param createdAt     建立时刻
 */
public record PaymentEventOutboxEntry(
    Long tenantId,
    String eventId,
    String eventType,
    String aggregateType,
    String aggregateId,
    String payloadJson,
    LocalDateTime createdAt) {

  /** 待投递状态（新事件一律以它落库，Relay 负责推进）。 */
  public static final String STATUS_PENDING = "PENDING";

  /** 新事件的初始重试次数。 */
  public static final int INITIAL_RETRY_COUNT = 0;
}
