package io.openware.platform.marketing.api.converter;

import io.openware.platform.marketing.api.dto.CampaignView;
import io.openware.platform.marketing.api.dto.ConsentView;
import io.openware.platform.marketing.domain.campaign.model.MktCampaign;
import io.openware.platform.marketing.domain.consent.model.MktConsent;

/**
 * 领域模型到 API 契约的转换（standards/10“转换层职责切分”）。
 *
 * <p>转换收在这里而不是放进 DTO 本身：DTO 只描述对外形状，让它 import 领域模型会把 API 契约反向绑到
 * 领域对象上。
 */
public final class MarketingConverter {

  private MarketingConverter() {
  }

  /** 活动领域模型转对外视图。 */
  public static CampaignView toView(MktCampaign campaign) {
    if (campaign == null) {
      return null;
    }
    return new CampaignView(campaign.id(), campaign.tenantId(), campaign.campaignType(), campaign.name(),
        campaign.startAt(), campaign.endAt(), campaign.budgetAmount(), campaign.fundingParty(),
        campaign.status(), campaign.ruleSnapshotJson(), campaign.version());
  }

  /** 同意领域模型转对外视图。 */
  public static ConsentView toView(MktConsent consent) {
    if (consent == null) {
      return null;
    }
    return new ConsentView(consent.id(), consent.accountId(), consent.tenantId(), consent.consentType(),
        consent.channel(), consent.policyVersion(), consent.granted(), consent.source(),
        consent.occurredAt(), consent.withdrawnAt(), consent.version());
  }
}
