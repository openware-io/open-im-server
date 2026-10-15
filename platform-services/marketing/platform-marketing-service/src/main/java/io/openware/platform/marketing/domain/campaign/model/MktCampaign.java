package io.openware.platform.marketing.domain.campaign.model;

import java.time.LocalDateTime;

/**
 * 营销活动（领域模型）。
 *
 * <p>审计字段（createdBy/At、updatedBy/At）不在此承载：它们是记账信息，由持久化适配器填充。
 * {@code version} 保留：它是并发语义，属业务可见。
 *
 * @param id               活动标识
 * @param tenantId         归属租户；{@code null} 表示平台级活动
 * @param campaignType     活动类型
 * @param name             活动名称
 * @param startAt          生效起点；{@code null} 表示不设下界
 * @param endAt            生效终点；{@code null} 表示不设上界
 * @param budgetAmount     预算金额（分）
 * @param fundingParty     出资方
 * @param status           状态：ACTIVE / 其它
 * @param ruleSnapshotJson 规则快照
 * @param version          版本号
 */
public record MktCampaign(
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

  /** 活动处于可用状态。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** 活动在给定时刻是否处于生效窗口内（窗口两端可为空，表示该端不设界）。 */
  public boolean effectiveAt(LocalDateTime moment) {
    if (startAt != null && startAt.isAfter(moment)) {
      return false;
    }
    return endAt == null || !endAt.isBefore(moment);
  }

  /** 是否归属给定租户；{@code null} 租户为平台级活动，对所有租户可见。 */
  public boolean visibleTo(Long tenant) {
    return tenantId == null || tenantId.equals(tenant);
  }
}
