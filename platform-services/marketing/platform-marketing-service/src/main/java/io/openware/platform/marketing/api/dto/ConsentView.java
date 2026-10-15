package io.openware.platform.marketing.api.dto;

import java.time.LocalDateTime;

/**
 * 营销同意响应（API 契约）。
 *
 * <p>此前接口直接返回持久化对象 {@code MktConsentPo}。本类型固定对外形状，且按 D2 规则**不暴露**
 * 记账字段（createdBy/At、updatedBy/At）。
 *
 * <p>{@code occurredAt} 与 {@code withdrawnAt} 保留：它们是同意发生与撤回的**业务时间**；
 * {@code version} 亦然（并发语义）。
 */
public record ConsentView(
    Long id,
    Long accountId,
    Long tenantId,
    String consentType,
    String channel,
    String policyVersion,
    boolean granted,
    String source,
    LocalDateTime occurredAt,
    LocalDateTime withdrawnAt,
    Integer version) {
}
