package io.openware.platform.identity.domain.account.model;

import java.time.LocalDateTime;

/**
 * 资料同步记录（领域模型）：按事件幂等记录一次外部资料同步。
 *
 * <p>{@code occurredAt} 保留在领域模型上：它是**业务事实**（该次同步什么时候发生），不同于
 * {@code createdAt} 这类记账时间，因此按 standards/10 的 D2 规则不走适配器填充。
 *
 * @param id          记录主键
 * @param eventId     事件标识，消费者按它幂等
 * @param accountId   所属账号
 * @param provider    提供方标识
 * @param profileJson 同步到的资料快照（JSON）
 * @param occurredAt  该次同步的业务发生时间
 */
public record ProfileSyncRecord(
    Long id,
    String eventId,
    Long accountId,
    String provider,
    String profileJson,
    LocalDateTime occurredAt) {
}
