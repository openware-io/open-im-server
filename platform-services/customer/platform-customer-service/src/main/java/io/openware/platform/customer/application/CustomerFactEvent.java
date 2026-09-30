package io.openware.platform.customer.application;

/** Customer 域可可靠投递的最小业务事实；载荷不得包含姓名、手机号等 PII。 */
public record CustomerFactEvent(String eventType, String aggregateType, String aggregateId, String payloadJson) {
}
