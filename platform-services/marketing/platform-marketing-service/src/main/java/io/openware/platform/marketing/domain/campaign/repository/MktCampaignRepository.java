package io.openware.platform.marketing.domain.campaign.repository;

import io.openware.platform.marketing.domain.campaign.model.MktCampaign;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 营销活动仓储端口。
 *
 * <p>方法表达业务意图：调用方要的是「此刻对某租户可用的活动」，不是可拼接的查询条件。
 * 生效窗口与可见范围（平台级 + 本租户）的判定收在这里，不许调用方在应用层拼 Wrapper
 * （见 standards/10“端口命名与契约”）。
 */
public interface MktCampaignRepository {

  /**
   * 列出**此刻可用**的活动：状态可用、处于生效窗口内，且归属平台或给定租户。
   *
   * @param tenantId 当前租户；{@code null} 表示只看平台级活动
   */
  List<MktCampaign> findAvailable(Long tenantId, LocalDateTime moment);
}
