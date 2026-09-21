package com.gvchat.platform.marketing.api.controller;

import com.gvchat.platform.marketing.application.ConsentApplicationService;
import com.gvchat.platform.marketing.infra.persistence.po.MktConsentPo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 营销同意 API（对齐 SAAS_PLATFORM_05_API.md §8）。营销授权与履约通知分开存储。
 */
@RestController
@RequestMapping("/business/marketing/consent")
public class MarketingConsentController {
    private final ConsentApplicationService consentService;

    public MarketingConsentController(ConsentApplicationService consentService) { this.consentService = consentService; }

    /** 查询账号营销同意记录。 */
    @GetMapping
    public List<MktConsentPo> list(@RequestParam Long accountId) {
        return consentService.list(accountId);
    }

    /** 设置/撤回营销同意（upsert）。 */
    @PutMapping
    public MktConsentPo upsert(@RequestBody UpsertConsentRequest req) {
        return consentService.upsert(req.accountId(), req.consentType(), req.channel(),
                req.granted(), req.policyVersion(), req.source());
    }

    public record UpsertConsentRequest(Long accountId, String consentType, String channel,
                                       Boolean granted, String policyVersion, String source) {}
}
