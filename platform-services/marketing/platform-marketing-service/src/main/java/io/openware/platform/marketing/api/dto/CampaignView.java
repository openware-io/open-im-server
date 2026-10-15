package io.openware.platform.marketing.api.dto;

import java.time.LocalDateTime;

/**
 * 营销活动响应（API 契约）。
 *
 * <p>此前接口直接返回持久化对象 {@code MktCampaignPo}，使对外契约与表映射耦合。本类型固定对外形状，
 * 且按 standards/10 的 D2 规则**不暴露**记账字段（createdBy/At、updatedBy/At）：它们是持久化记账信息，
 * 不是活动的业务事实。
 *
 * @param version 版本号：它是并发语义，属业务可见，因此保留。
 */
public record CampaignView(
    Long id,
    Long tenantId,
    String campaignType,
    String name,
    LocalDateTime startAt,
    LocalDateTime endAt,
    Long budgetAmount,
    String fundingParty,
    String status,
    String ruleSnapshotJson,
    Integer version) {
}
