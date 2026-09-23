package io.openware.platform.marketing.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.marketing.infra.persistence.mapper.CampaignMapper;
import io.openware.platform.marketing.infra.persistence.po.MktCampaignPo;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 营销活动应用服务（脚手架）。可用活动 = 平台活动 + 当前租户活动，且处于 ACTIVE 与有效时间窗内。
 */
@Service
public class CampaignApplicationService {
    private final CampaignMapper campaignMapper;

    public CampaignApplicationService(CampaignMapper campaignMapper) { this.campaignMapper = campaignMapper; }

    public List<MktCampaignPo> available() {
        Long tenantId = TenantContextHolder.tenantIdOrNull();
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<MktCampaignPo> qw = new LambdaQueryWrapper<>();
        qw.eq(MktCampaignPo::getStatus, "ACTIVE");
        qw.and(w -> w.isNull(MktCampaignPo::getStartAt).or().le(MktCampaignPo::getStartAt, now));
        qw.and(w -> w.isNull(MktCampaignPo::getEndAt).or().ge(MktCampaignPo::getEndAt, now));
        if (tenantId == null) {
            qw.isNull(MktCampaignPo::getTenantId);
        } else {
            qw.and(w -> w.isNull(MktCampaignPo::getTenantId).or().eq(MktCampaignPo::getTenantId, tenantId));
        }
        qw.orderByDesc(MktCampaignPo::getId);
        return campaignMapper.selectList(qw);
    }
}
