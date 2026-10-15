package io.openware.platform.marketing.api.controller;

import io.openware.platform.marketing.api.converter.MarketingConverter;
import io.openware.platform.marketing.api.dto.CampaignView;
import io.openware.platform.marketing.application.CampaignApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 营销活动 API（对齐 SAAS_PLATFORM_05_API.md §8）。网关统一加 /api/v1，本服务只暴露 /business/**。
 *
 * <p>响应经 {@link MarketingConverter} 固定为对外契约，不再直接返回持久化对象。
 */
@RestController
@RequestMapping("/business/campaigns")
public class CampaignController {
    private final CampaignApplicationService campaignService;

    public CampaignController(CampaignApplicationService campaignService) { this.campaignService = campaignService; }

    /** 可用营销活动（平台 + 当前租户，ACTIVE 且有效时间窗内）。 */
    @GetMapping
    public List<CampaignView> available() {
        return campaignService.available().stream().map(MarketingConverter::toView).toList();
    }
}
