package io.openware.platform.marketing.application;

import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.marketing.domain.campaign.model.MktCampaign;
import io.openware.platform.marketing.domain.campaign.repository.MktCampaignRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 营销活动应用服务（脚手架）。可用活动 = 平台活动 + 当前租户活动，且处于 ACTIVE 与有效时间窗内。
 *
 * <p>窗口与可见范围的判定收在端口（{@link MktCampaignRepository#findAvailable}），应用层只负责取当前租户
 * 上下文与调用领域能力，不再拼接查询条件。
 */
@Service
public class CampaignApplicationService {
    private final MktCampaignRepository campaignRepository;

    public CampaignApplicationService(MktCampaignRepository campaignRepository) {
        this.campaignRepository = campaignRepository;
    }

    public List<MktCampaign> available() {
        Long tenantId = TenantContextHolder.tenantIdOrNull();
        return campaignRepository.findAvailable(tenantId, LocalDateTime.now());
    }
}
